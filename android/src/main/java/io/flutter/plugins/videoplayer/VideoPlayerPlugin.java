// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import android.content.Context;
import android.util.LongSparseArray;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.FlutterInjector;
import io.flutter.Log;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugins.videoplayer.platformview.PlatformVideoViewFactory;
import io.flutter.plugins.videoplayer.platformview.PlatformViewVideoPlayer;
import io.flutter.plugins.videoplayer.texture.TextureVideoPlayer;
import io.flutter.view.TextureRegistry;

/** Android platform implementation of the VideoPlayerPlugin. */
public class VideoPlayerPlugin implements FlutterPlugin, AndroidVideoPlayerApi {
  private static final String TAG = "VideoPlayerPlugin";
  private final LongSparseArray<VideoPlayer> videoPlayers = new LongSparseArray<>();
  private FlutterState flutterState;
  private final VideoPlayerOptions sharedOptions = new VideoPlayerOptions();
  private long nextPlayerIdentifier = 1;

  /** Register this with the v2 embedding for the plugin to respond to lifecycle callbacks. */
  public VideoPlayerPlugin() {}

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
    final FlutterInjector injector = FlutterInjector.instance();
    this.flutterState =
            new FlutterState(
                    binding.getApplicationContext(),
                    binding.getBinaryMessenger(),
                    injector.flutterLoader()::getLookupKeyForAsset,
                    injector.flutterLoader()::getLookupKeyForAsset,
                    binding.getTextureRegistry());
    flutterState.startListening(this, binding.getBinaryMessenger());

    binding
            .getPlatformViewRegistry()
            .registerViewFactory(
                    "plugins.flutter.dev/video_player_android",
                    new PlatformVideoViewFactory(videoPlayers::get));
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    if (flutterState == null) {
      Log.wtf(TAG, "Detached from the engine before registering to it.");
    }
    flutterState.stopListening(binding.getBinaryMessenger());
    flutterState = null;
    onDestroy();
  }

  private void disposeAllPlayers() {
    for (int i = 0; i < videoPlayers.size(); i++) {
      videoPlayers.valueAt(i).dispose();
    }
    videoPlayers.clear();
  }

  public void onDestroy() {
    // The whole FlutterView is being destroyed. Here we release resources acquired for all
    // instances of VideoPlayer.
    disposeAllPlayers();
  }

  @Override
  public void initialize() {
    disposeAllPlayers();
  }

  @Override
  public long createForPlatformView(@NonNull CreationOptions options) {
    final VideoAsset videoAsset = videoAssetWithOptions(options);

    long id = nextPlayerIdentifier++;
    final String streamInstance = Long.toString(id);
    VideoPlayer videoPlayer =
            PlatformViewVideoPlayer.create(
                    flutterState.applicationContext,
                    VideoPlayerEventCallbacks.bindTo(flutterState.binaryMessenger, streamInstance),
                    videoAsset,
                    sharedOptions);

    registerPlayerInstance(videoPlayer, id);
    return id;
  }

  @Override
  public @NonNull TexturePlayerIds createForTextureView(@NonNull CreationOptions options) {
    final VideoAsset videoAsset = videoAssetWithOptions(options);

    // Defensive initialization with small backoff; helps avoid flaky init crashes on some ROMs.
    final long id = nextPlayerIdentifier++;
    final String streamInstance = Long.toString(id);

    final int maxAttempts = 3;    // number of retries
    final long baseSleepMs = 80L; // incremental backoff base delay
    TextureRegistry.SurfaceProducer handle = null;
    VideoPlayer videoPlayer = null;
    RuntimeException lastError = null;

    for (int attempt = 0; attempt < maxAttempts; attempt++) {
      try {
        handle = flutterState.textureRegistry.createSurfaceProducer();
        videoPlayer =
                TextureVideoPlayer.create(
                        flutterState.applicationContext,
                        VideoPlayerEventCallbacks.bindTo(flutterState.binaryMessenger, streamInstance),
                        handle,
                        videoAsset,
                        sharedOptions);

        // Success — register and return.
        registerPlayerInstance(videoPlayer, id);
        return new TexturePlayerIds(id, handle.id());
      } catch (RuntimeException e) {
        lastError = e;
        Log.w(TAG, "Video init failed on attempt " + (attempt + 1) + "/" + maxAttempts + ": " + e);
        // Clean up partially initialized handle before retrying.
        if (handle != null) {
          try {
            handle.release();
          } catch (Throwable ignored) {}
          handle = null;
        }
        // Small incremental backoff.
        try {
          Thread.sleep(baseSleepMs * (attempt + 1));
        } catch (InterruptedException ignored) {}
      }
    }

    // OPTIONAL fallback to platform view if your Dart side supports it (no textureId needed).
    // long pvId = createForPlatformView(options);
    // return new TexturePlayerIds(pvId, -1L);

    // If we reach here — all attempts failed. Surface the most recent error.
    throw (lastError != null)
            ? lastError
            : new RuntimeException("Video player initialization failed (unknown reason)");
  }

  private @NonNull VideoAsset videoAssetWithOptions(@NonNull CreationOptions options) {
    final @NonNull String uri = options.getUri();
    if (uri.startsWith("asset:")) {
      return VideoAsset.fromAssetUrl(uri);
    } else if (uri.startsWith("rtsp:")) {
      return VideoAsset.fromRtspUrl(uri);
    } else {
      VideoAsset.StreamingFormat streamingFormat = VideoAsset.StreamingFormat.UNKNOWN;
      PlatformVideoFormat formatHint = options.getFormatHint();
      if (formatHint != null) {
        switch (formatHint) {
          case SS:
            streamingFormat = VideoAsset.StreamingFormat.SMOOTH;
            break;
          case DASH:
            streamingFormat = VideoAsset.StreamingFormat.DYNAMIC_ADAPTIVE;
            break;
          case HLS:
            streamingFormat = VideoAsset.StreamingFormat.HTTP_LIVE;
            break;
        }
      }
      return VideoAsset.fromRemoteUrl(
              uri, streamingFormat, options.getHttpHeaders(), options.getUserAgent());
    }
  }

  private void registerPlayerInstance(VideoPlayer player, long id) {
    // Set up the instance-specific API handler, and make sure it is removed when the player is
    // disposed.
    BinaryMessenger messenger = flutterState.binaryMessenger;
    final String channelSuffix = Long.toString(id);
    VideoPlayerInstanceApi.Companion.setUp(messenger, player, channelSuffix);
    player.setDisposeHandler(
            () -> VideoPlayerInstanceApi.Companion.setUp(messenger, null, channelSuffix));

    videoPlayers.put(id, player);
  }

  @NonNull
  private VideoPlayer getPlayer(long playerId) {
    VideoPlayer player = videoPlayers.get(playerId);

    // Avoid a very ugly un-debuggable NPE that results in returning a null player.
    if (player == null) {
      String message = "No player found with playerId <" + playerId + ">";
      if (videoPlayers.size() == 0) {
        message += " and no active players created by the plugin.";
      }
      throw new IllegalStateException(message);
    }

    return player;
  }

  @Override
  public void dispose(long playerId) {
    VideoPlayer player = getPlayer(playerId);
    player.dispose();
    videoPlayers.remove(playerId);
  }

  @Override
  public void setMixWithOthers(boolean mixWithOthers) {
    sharedOptions.mixWithOthers = mixWithOthers;
  }

  @Override
  public @NonNull String getLookupKeyForAsset(@NonNull String asset, @Nullable String packageName) {
    return packageName == null
            ? flutterState.keyForAsset.get(asset)
            : flutterState.keyForAssetAndPackageName.get(asset, packageName);
  }

  private interface KeyForAssetFn {
    String get(String asset);
  }

  private interface KeyForAssetAndPackageName {
    String get(String asset, String packageName);
  }

  private static final class FlutterState {
    final Context applicationContext;
    final BinaryMessenger binaryMessenger;
    final KeyForAssetFn keyForAsset;
    final KeyForAssetAndPackageName keyForAssetAndPackageName;
    final TextureRegistry textureRegistry;

    FlutterState(
            Context applicationContext,
            BinaryMessenger messenger,
            KeyForAssetFn keyForAsset,
            KeyForAssetAndPackageName keyForAssetAndPackageName,
            TextureRegistry textureRegistry) {
      this.applicationContext = applicationContext;
      this.binaryMessenger = messenger;
      this.keyForAsset = keyForAsset;
      this.keyForAssetAndPackageName = keyForAssetAndPackageName;
      this.textureRegistry = textureRegistry;
    }

    void startListening(VideoPlayerPlugin methodCallHandler, BinaryMessenger messenger) {
      AndroidVideoPlayerApi.Companion.setUp(messenger, methodCallHandler);
    }

    void stopListening(BinaryMessenger messenger) {
      AndroidVideoPlayerApi.Companion.setUp(messenger, null);
    }
  }
}
