package org.webrtc;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.function.BiConsumer;

/** Reset the hardware decoder safely when an encoder restarts or replaces SPS/resolution. */
public final class RecoverableVideoDecoder implements VideoDecoder {
  private final Function<Settings, VideoDecoder> primary;
  private final Function<Settings, VideoDecoder> standard;
  private final boolean alternate;
  private final BiConsumer<Settings, String> onAlternateFrame;
  private final DecoderRecoveryController controller;
  private final AtomicBoolean resetRequested = new AtomicBoolean();
  private final DecoderRecoveryController.ResetRequest request = () -> resetRequested.set(true);
  private volatile VideoDecoder decoder;
  private Settings settings;
  private Callback callback;
  private boolean released;
  private boolean initialized;
  private boolean standardOnly;
  private boolean repaired;
  private boolean preferStandard;
  private boolean releaseFailed;
  private volatile OutputEpoch output;

  private static final class OutputEpoch {
    volatile boolean receivedFrame;
  }

  public RecoverableVideoDecoder(Supplier<VideoDecoder> primary, Supplier<VideoDecoder> standard,
                                 DecoderRecoveryController controller) {
    this(primary, standard, controller, create(primary));
  }

  RecoverableVideoDecoder(Supplier<VideoDecoder> primary, Supplier<VideoDecoder> standard,
                          DecoderRecoveryController controller, VideoDecoder initial) {
    this(settings -> primary.get(), standard == null ? null : settings -> standard.get(),
        controller, initial, false, null);
  }

  RecoverableVideoDecoder(Function<Settings, VideoDecoder> primary, Function<Settings, VideoDecoder> fallback,
      DecoderRecoveryController controller, VideoDecoder initial, boolean alternate,
      BiConsumer<Settings, String> onAlternateFrame) {
    this.primary = primary; this.standard = fallback; this.controller = controller;
    this.alternate = alternate; this.onAlternateFrame = onAlternateFrame;
    decoder = initial;
  }
  @Override public VideoCodecStatus initDecode(Settings settings, Callback callback) {
    this.settings = settings; this.callback = callback; released = false;
    initialized = false;
    controller.detach(request);
    if (releaseFailed) return VideoCodecStatus.FALLBACK_SOFTWARE;
    VideoCodecStatus result = decoder == null ? VideoCodecStatus.FALLBACK_SOFTWARE : initializeCurrent();
    if (result != VideoCodecStatus.OK) {
      controller.record(path() + "_INIT_" + result);
      if (standardOnly || standard == null || releaseCurrent() != VideoCodecStatus.OK) return result;
      standardOnly = true;
      decoder = create(standard, settings);
      if (decoder == null) {
        controller.record(path() + "_UNAVAILABLE");
        return VideoCodecStatus.FALLBACK_SOFTWARE;
      }
      result = initializeCurrent();
      controller.record(path() + "_INIT_" + result);
    }
    initialized = result == VideoCodecStatus.OK;
    if (initialized) controller.attach(request);
    return result;
  }
  @Override public VideoCodecStatus decode(EncodedImage image, DecodeInfo info) {
    if (released || !initialized || decoder == null) return VideoCodecStatus.UNINITIALIZED;
    boolean key = image.frameType == EncodedImage.FrameType.VideoFrameKey;
    if (key && resetRequested.compareAndSet(true, false)) {
      boolean changePath = standard != null && !standardOnly && (preferStandard || (repaired && !output.receivedFrame));
      if (!restart(image, changePath)) return VideoCodecStatus.FALLBACK_SOFTWARE;
    }
    VideoCodecStatus result = decodeCurrent(image, info);
    if (!standardOnly && standard != null && (result == VideoCodecStatus.FALLBACK_SOFTWARE ||
        result == VideoCodecStatus.TIMEOUT || (key && result == VideoCodecStatus.ERROR))) {
      controller.record(path() + "_DECODE_" + result);
      if (key) {
        return restart(image, true) ? decodeCurrent(image, info) : VideoCodecStatus.FALLBACK_SOFTWARE;
      }
      preferStandard = true;
      resetRequested.set(true);
      return VideoCodecStatus.ERROR;
    }
    return result;
  }

  private boolean restart(EncodedImage image, boolean changePath) {
    initialized = false;
    controller.detach(request);
    if (releaseCurrent() != VideoCodecStatus.OK) {
      controller.record("RELEASE_FAILED");
      return false;
    }
    standardOnly |= changePath;
    preferStandard = false;
    repaired = true;
    controller.record(path() + "_RESET");
    Settings next = new Settings(settings.numberOfCores,
        image.encodedWidth > 0 ? image.encodedWidth : settings.width,
        image.encodedHeight > 0 ? image.encodedHeight : settings.height);
    decoder = create(standardOnly ? standard : primary, next);
    return initDecode(next, callback) == VideoCodecStatus.OK;
  }

  private VideoCodecStatus initializeCurrent() {
    OutputEpoch epoch = new OutputEpoch();
    output = epoch;
    Callback downstream = callback;
    Settings configured = settings;
    String learned = standardOnly && onAlternateFrame != null ? decoder.getImplementationName() : null;
    try {
      return decoder.initDecode(settings, (frame, decodeTime, qp) -> {
        if (output != epoch) return;
        if (!epoch.receivedFrame) {
          epoch.receivedFrame = true;
          if (learned != null) onAlternateFrame.accept(configured, learned);
        }
        downstream.onDecodedFrame(frame, decodeTime, qp);
      });
    } catch (RuntimeException failure) {
      return VideoCodecStatus.ERROR;
    }
  }

  private VideoCodecStatus decodeCurrent(EncodedImage image, DecodeInfo info) {
    try { return decoder.decode(image, info); }
    catch (RuntimeException failure) { return VideoCodecStatus.ERROR; }
  }

  private static VideoDecoder create(Supplier<VideoDecoder> supplier) {
    try { return supplier.get(); }
    catch (RuntimeException unavailable) { return null; }
  }

  private static VideoDecoder create(Function<Settings, VideoDecoder> factory, Settings settings) {
    try { return factory.apply(settings); }
    catch (RuntimeException unavailable) { return null; }
  }

  private String path() { return standardOnly ? (alternate ? "ALTERNATE" : "STANDARD") : "PREFERRED"; }
  @Override public VideoCodecStatus release() {
    if (released) return VideoCodecStatus.OK;
    released = true; initialized = false;
    controller.detach(request); resetRequested.set(false);
    return releaseCurrent();
  }
  private VideoCodecStatus releaseCurrent() {
    output = null;
    VideoDecoder previous = decoder;
    decoder = null;
    if (previous == null) return VideoCodecStatus.OK;
    VideoCodecStatus result;
    try { result = previous.release(); }
    catch (RuntimeException failure) { result = VideoCodecStatus.ERROR; }
    if (result != VideoCodecStatus.OK) releaseFailed = true;
    return result;
  }
  @Override public String getImplementationName() {
    VideoDecoder current = decoder;
    return current == null ? "decoder" : current.getImplementationName();
  }
}
