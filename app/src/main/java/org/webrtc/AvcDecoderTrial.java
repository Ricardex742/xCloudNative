package org.webrtc;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Opt-in routing hints for one playback, including its reconnects. Only decoded output teaches a route. */
public final class AvcDecoderTrial {
  private final Map<String, String> working = new LinkedHashMap<>();
  private volatile String diagnostic = "WAITING";
  private static String key(String profileLevel, int width, int height) {
    return profileLevel + ":" + width + "x" + height;
  }

  synchronized String trained(String profileLevel, int width, int height) {
    return working.get(key(profileLevel, width, height));
  }

  synchronized void remember(String profileLevel, int width, int height, String component) {
    if (component == null || component.isEmpty()) return;
    String route = key(profileLevel, width, height);
    working.remove(route);
    if (working.size() == 8) working.remove(working.keySet().iterator().next());
    working.put(route, component);
    diagnostic = "FRAME/" + component;
  }

  private synchronized void forget(String profileLevel, int width, int height, String component) {
    working.remove(key(profileLevel, width, height), component);
  }

  public String diagnostics() { return "DECODER_TRIAL/" + diagnostic; }

  VideoDecoder route(EglBase.Context context, VideoCodecInfo format, int width, int height,
      VideoDecoder original, VideoDecoderFactory defaultHardware, DecoderRecoveryController recovery) {
    String profileLevel = format.params.getOrDefault("profile-level-id", "").toLowerCase(Locale.ROOT);
    int profile = switch (profileLevel.length() == 6 ? profileLevel.substring(0, 4) : "") {
      case "4200", "4280", "42c0", "42e0" -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;
      case "4d00" -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain;
      case "6400", "640c" -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;
      default -> 0;
    };
    int required = H264MainSupport.requiredLevel(width, height, 60);
    if (profile == 0 || required < 0 || !profileLevel.matches("[0-9a-f]{6}")) return null;
    int offered = Integer.parseInt(profileLevel.substring(4), 16);
    if (offered > 0x34) return null;

    // Codec IDs in SDP are hexadecimal encodings of decimal level numbers, e.g. 0x1f = 31.
    String primary = original.getImplementationName();
    String canonical = canonicalComponent(primary);
    String remembered = trained(profileLevel, width, height);
    Function<VideoDecoder.Settings, VideoDecoder> alternative = settings -> {
      int w = settings.width > 0 ? settings.width : width;
      int h = settings.height > 0 ? settings.height : height;
      int minimum = H264MainSupport.requiredLevel(w, h, 60);
      if (minimum < 0) return null;
      int level = H264MainSupport.androidLevel(Math.max(minimum, offered));
      if (level == Integer.MAX_VALUE) return null;
      return new HardwareVideoDecoderFactory(context, info ->
          supportsDifferentComponent(info, canonical, profile, level, w, h)).createDecoder(format);
    };
    VideoDecoder preferred = null;
    if (remembered != null && !remembered.equals(primary)) {
      try {
        VideoDecoder candidate = alternative.apply(new VideoDecoder.Settings(1, width, height));
        if (candidate != null && remembered.equals(candidate.getImplementationName())) preferred = candidate;
        else if (candidate != null) candidate.release();
      } catch (RuntimeException unavailable) {
        // The standard route is still available.
      }
    }
    if (preferred != null) {
      VideoDecoder learned = preferred;
      diagnostic = "REUSE/" + learned.getImplementationName();
      return new RecoverableVideoDecoder(settings -> {
            VideoDecoder candidate = alternative.apply(settings);
            if (candidate == null || remembered.equals(candidate.getImplementationName())) return candidate;
            candidate.release();
            return null;
          }, settings -> {
            forget(profileLevel, width, height, remembered);
            diagnostic = "STANDARD/" + primary;
            return defaultHardware.createDecoder(format);
          }, recovery, learned, false, null);
    }
    return new RecoverableVideoDecoder(settings -> defaultHardware.createDecoder(format), settings -> {
      VideoDecoder alternate = alternative.apply(settings);
      diagnostic = alternate == null ? "NO_ALTERNATE/" + primary : "TRY/" + alternate.getImplementationName();
      return alternate;
    }, recovery, original, true, (configured, name) ->
        remember(profileLevel, configured.width > 0 ? configured.width : width,
            configured.height > 0 ? configured.height : height, name));
  }

  private static String canonicalComponent(String name) {
    if (Build.VERSION.SDK_INT >= 29) {
      for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
        if (info.getName().equals(name)) return info.getCanonicalName();
      }
    }
    return name;
  }

  private static boolean supportsDifferentComponent(MediaCodecInfo info, String primary, int profile,
      int level, int width, int height) {
    if (info.isEncoder() || info.getName().equals(primary) ||
        !MediaCodecUtils.isHardwareAccelerated(info) || MediaCodecUtils.isSoftwareOnly(info)) return false;
    // Aliases of the same MediaCodec are not independent fallback components.
    if (Build.VERSION.SDK_INT >= 29 && info.isAlias()) return false;
    try {
      MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType("video/avc");
      if (caps.getVideoCapabilities() == null || !caps.getVideoCapabilities().areSizeAndRateSupported(width, height, 60))
        return false;
      for (MediaCodecInfo.CodecProfileLevel declared : caps.profileLevels) {
        if (declared.profile == profile && declared.level >= level) return true;
      }
    } catch (RuntimeException unavailable) {
      // Ignore a codec whose advertised capabilities cannot be read.
    }
    return false;
  }
}
