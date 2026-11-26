package org.webrtc;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bridge for the pinned WebRTC M137 codec wrapper. No vendor flags or reflection. */
public final class LowLatencyDecoderFactory implements VideoDecoderFactory {
  private final EglBase.Context context;
  private final boolean enabled;
  private final Consumer<String> status;
  private final VideoDecoderFactory hardware;
  private final VideoDecoderFactory software;
  private final VideoDecoderFactory platformSoftware;
  private final DecoderRecoveryController recovery;
  private final AvcDecoderTrial avcTrial;
  private final int width;
  private final int height;

  public LowLatencyDecoderFactory(EglBase.Context context, boolean enabled, DecoderRecoveryController recovery, Consumer<String> status) {
    this(context, enabled, recovery, status, null, 0, 0);
  }

  LowLatencyDecoderFactory(EglBase.Context context, boolean enabled, DecoderRecoveryController recovery,
      Consumer<String> status, AvcDecoderTrial avcTrial, int width, int height) {
    this(context, enabled, recovery, status, new HardwareVideoDecoderFactory(context),
        new SoftwareVideoDecoderFactory(), new PlatformSoftwareVideoDecoderFactory(context), avcTrial, width, height);
  }

  LowLatencyDecoderFactory(EglBase.Context context, boolean enabled, DecoderRecoveryController recovery,
      Consumer<String> status, VideoDecoderFactory hardware, VideoDecoderFactory software,
      VideoDecoderFactory platformSoftware) {
    this(context, enabled, recovery, status, hardware, software, platformSoftware, null, 0, 0);
  }

  private LowLatencyDecoderFactory(EglBase.Context context, boolean enabled, DecoderRecoveryController recovery,
      Consumer<String> status, VideoDecoderFactory hardware, VideoDecoderFactory software,
      VideoDecoderFactory platformSoftware, AvcDecoderTrial avcTrial, int width, int height) {
    this.context = context;
    this.enabled = enabled;
    this.status = status;
    this.recovery = recovery;
    this.hardware = hardware;
    this.software = software;
    this.platformSoftware = platformSoftware;
    this.avcTrial = avcTrial; this.width = width; this.height = height;
  }

  @Override public VideoCodecInfo[] getSupportedCodecs() {
    // Keep the pinned default factory's order and deduplication.
    Set<VideoCodecInfo> supported = new LinkedHashSet<>();
    Collections.addAll(supported, software.getSupportedCodecs());
    Collections.addAll(supported, hardware.getSupportedCodecs());
    Collections.addAll(supported, platformSoftware.getSupportedCodecs());
    return supported.toArray(new VideoCodecInfo[0]);
  }

  @Override public VideoDecoder createDecoder(VideoCodecInfo codec) {
    VideoDecoder original = hardware.createDecoder(codec);
    VideoDecoder fallback = software.createDecoder(codec);
    if (fallback == null) fallback = platformSoftware.createDecoder(codec);
    if (original == null) return fallback;
    if (!enabled && avcTrial != null && codec.getName().equalsIgnoreCase("H264")) {
      VideoDecoder routed = null;
      try {
        routed = avcTrial.route(context, codec, width, height, original, hardware, recovery);
      } catch (RuntimeException unavailable) {
        recovery.record("ALTERNATE_CAPABILITIES_UNAVAILABLE");
      }
      if (routed != null) return fallback == null ? routed : new VideoDecoderFallback(fallback, routed);
    }
    Supplier<VideoDecoder> normal = () -> hardware.createDecoder(codec);
    Supplier<VideoDecoder> preferred = normal;
    if (enabled && codec.getName().equalsIgnoreCase("H264")) {
      try {
        String name = original.getImplementationName();
        MediaCodecInfo chosen = null;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
          if (!info.isEncoder() && info.getName().equals(name)) { chosen = info; break; }
        }
        if (chosen != null) {
          MediaCodecInfo.CodecCapabilities caps = chosen.getCapabilitiesForType("video/avc");
          Integer color = MediaCodecUtils.selectColorFormat(MediaCodecUtils.DECODER_COLOR_FORMATS, caps);
          if (color != null) {
            boolean lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency);
            MediaCodecWrapperFactory wrappers = new MediaCodecWrapperFactoryImpl();
            preferred = () -> new AndroidVideoDecoder(
                codecName -> new RealtimeDecoderCodec(wrappers.createByCodecName(codecName), lowLatency, status),
                name, VideoCodecMimeType.H264, color, context);
            normal = () -> {
              status.accept("Codec: padrão após rejeição das opções de tempo real");
              return hardware.createDecoder(codec);
            };
            status.accept("Codec: aguardando configuração de tempo real");
          } else status.accept("Codec: formato não disponível; decoder padrão");
        } else status.accept("Codec: capacidades não encontradas; decoder padrão");
      } catch (RuntimeException error) {
        status.accept("Codec: capacidades indisponíveis; decoder padrão");
      }
    }
    VideoDecoder recoverable = preferred == normal
        ? new RecoverableVideoDecoder(normal, null, recovery, original)
        : new RecoverableVideoDecoder(preferred, normal, recovery);
    // The JNI fallback must contain only a software decoder for this exact format.
    return fallback == null ? recoverable : new VideoDecoderFallback(fallback, recoverable);
  }

}
