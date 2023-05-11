/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.transformer;

import static com.google.android.exoplayer2.transformer.Transformer.PROGRESS_STATE_AVAILABLE;
import static com.google.android.exoplayer2.transformer.Transformer.PROGRESS_STATE_NOT_STARTED;
import static com.google.android.exoplayer2.transformer.TransformerUtil.getProcessedTrackType;
import static com.google.android.exoplayer2.util.Assertions.checkArgument;
import static com.google.android.exoplayer2.util.Assertions.checkNotNull;
import static com.google.android.exoplayer2.util.Assertions.checkState;
import static com.google.android.exoplayer2.util.Assertions.checkStateNotNull;

import android.graphics.Bitmap;
import android.os.Looper;
import android.view.Surface;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.util.Clock;
import com.google.android.exoplayer2.util.HandlerWrapper;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.VideoFrameProcessor.OnInputFrameProcessedListener;
import com.google.android.exoplayer2.video.ColorInfo;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link AssetLoader} that is composed of a {@linkplain EditedMediaItemSequence sequence} of
 * non-overlapping {@linkplain AssetLoader asset loaders}.
 */
/* package */ final class SequenceAssetLoader implements AssetLoader, AssetLoader.Listener {

  private static final Format FORCE_AUDIO_TRACK_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_AAC)
          .setSampleRate(44100)
          .setChannelCount(2)
          .build();

  private final List<EditedMediaItem> editedMediaItems;
  private final boolean isLooping;
  private final boolean forceAudioTrack;
  private final AssetLoader.Factory assetLoaderFactory;
  private final HandlerWrapper handler;
  private final Listener sequenceAssetLoaderListener;
  /**
   * A mapping from track types to {@link SampleConsumer} instances.
   *
   * <p>This map never contains more than 2 entries, as the only track types allowed are audio and
   * video.
   */
  private final Map<Integer, SampleConsumer> sampleConsumersByTrackType;
  /**
   * A mapping from track types to {@link OnMediaItemChangedListener} instances.
   *
   * <p>This map never contains more than 2 entries, as the only track types allowed are audio and
   * video.
   */
  private final Map<Integer, OnMediaItemChangedListener> mediaItemChangedListenersByTrackType;

  private final ImmutableList.Builder<ExportResult.ProcessedInput> processedInputsBuilder;
  private final AtomicInteger nonEndedTracks;

  private boolean isCurrentAssetFirstAsset;
  private int currentMediaItemIndex;
  private AssetLoader currentAssetLoader;
  private boolean trackCountReported;
  private boolean decodeAudio;
  private boolean decodeVideo;
  private long totalDurationUs;
  private int sequenceLoopCount;
  private boolean audioLoopingEnded;
  private boolean videoLoopingEnded;
  private int processedInputsSize;
  private boolean released;

  private volatile long currentAssetDurationUs;
  private volatile long maxSequenceDurationUs;
  private volatile boolean isMaxSequenceDurationUsFinal;

  public SequenceAssetLoader(
      EditedMediaItemSequence sequence,
      boolean forceAudioTrack,
      AssetLoader.Factory assetLoaderFactory,
      Looper looper,
      Listener listener,
      Clock clock) {
    editedMediaItems = sequence.editedMediaItems;
    isLooping = sequence.isLooping;
    this.forceAudioTrack = forceAudioTrack;
    this.assetLoaderFactory = assetLoaderFactory;
    sequenceAssetLoaderListener = listener;
    handler = clock.createHandler(looper, /* callback= */ null);
    sampleConsumersByTrackType = new HashMap<>();
    mediaItemChangedListenersByTrackType = new HashMap<>();
    processedInputsBuilder = new ImmutableList.Builder<>();
    nonEndedTracks = new AtomicInteger();
    isCurrentAssetFirstAsset = true;
    // It's safe to use "this" because we don't start the AssetLoader before exiting the
    // constructor.
    @SuppressWarnings("nullness:argument.type.incompatible")
    AssetLoader currentAssetLoader =
        assetLoaderFactory.createAssetLoader(editedMediaItems.get(0), looper, /* listener= */ this);
    this.currentAssetLoader = currentAssetLoader;
  }

  // Methods called from TransformerInternal thread.

  @Override
  public void start() {
    currentAssetLoader.start();
    if (editedMediaItems.size() > 1 || isLooping) {
      sequenceAssetLoaderListener.onDurationUs(C.TIME_UNSET);
    }
  }

  @Override
  public @Transformer.ProgressState int getProgress(ProgressHolder progressHolder) {
    if (isLooping) {
      return Transformer.PROGRESS_STATE_UNAVAILABLE;
    }
    int progressState = currentAssetLoader.getProgress(progressHolder);
    int mediaItemCount = editedMediaItems.size();
    if (mediaItemCount == 1 || progressState == PROGRESS_STATE_NOT_STARTED) {
      return progressState;
    }

    int progress = currentMediaItemIndex * 100 / mediaItemCount;
    if (progressState == PROGRESS_STATE_AVAILABLE) {
      progress += progressHolder.progress / mediaItemCount;
    }
    progressHolder.progress = progress;
    return PROGRESS_STATE_AVAILABLE;
  }

  @Override
  public ImmutableMap<Integer, String> getDecoderNames() {
    return currentAssetLoader.getDecoderNames();
  }

  /**
   * Returns the partially or entirely {@linkplain ExportResult.ProcessedInput processed inputs}.
   */
  public ImmutableList<ExportResult.ProcessedInput> getProcessedInputs() {
    addCurrentProcessedInput();
    return processedInputsBuilder.build();
  }

  @Override
  public void release() {
    if (!released) {
      currentAssetLoader.release();
      released = true;
    }
  }

  private void addCurrentProcessedInput() {
    if ((sequenceLoopCount * editedMediaItems.size() + currentMediaItemIndex)
        >= processedInputsSize) {
      MediaItem mediaItem = editedMediaItems.get(currentMediaItemIndex).mediaItem;
      ImmutableMap<Integer, String> decoders = currentAssetLoader.getDecoderNames();
      processedInputsBuilder.add(
          new ExportResult.ProcessedInput(
              mediaItem, decoders.get(C.TRACK_TYPE_AUDIO), decoders.get(C.TRACK_TYPE_VIDEO)));
      processedInputsSize++;
    }
  }

  // Methods called from AssetLoader threads.

  /**
   * Adds an {@link OnMediaItemChangedListener} for the given track type.
   *
   * <p>There can't be more than one {@link OnMediaItemChangedListener} for the same track type.
   *
   * <p>Must be called from the thread used by the current {@link AssetLoader} to pass data to the
   * {@link SampleConsumer}.
   *
   * @param onMediaItemChangedListener The {@link OnMediaItemChangedListener}.
   * @param trackType The {@link C.TrackType} for which to listen to {@link MediaItem} change
   *     events. Must be {@link C#TRACK_TYPE_AUDIO} or {@link C#TRACK_TYPE_VIDEO}.
   */
  public void addOnMediaItemChangedListener(
      OnMediaItemChangedListener onMediaItemChangedListener, @C.TrackType int trackType) {
    checkArgument(trackType == C.TRACK_TYPE_AUDIO || trackType == C.TRACK_TYPE_VIDEO);
    checkArgument(mediaItemChangedListenersByTrackType.get(trackType) == null);
    mediaItemChangedListenersByTrackType.put(trackType, onMediaItemChangedListener);
  }

  @Override
  public boolean onTrackAdded(Format inputFormat, @SupportedOutputTypes int supportedOutputTypes) {
    boolean isAudio = getProcessedTrackType(inputFormat.sampleMimeType) == C.TRACK_TYPE_AUDIO;
    if (!isCurrentAssetFirstAsset) {
      return isAudio ? decodeAudio : decodeVideo;
    }

    boolean addForcedAudioTrack = forceAudioTrack && nonEndedTracks.get() == 1 && !isAudio;

    if (!trackCountReported) {
      int trackCount = nonEndedTracks.get() + (addForcedAudioTrack ? 1 : 0);
      sequenceAssetLoaderListener.onTrackCount(trackCount);
      trackCountReported = true;
    }

    boolean decodeOutput =
        sequenceAssetLoaderListener.onTrackAdded(inputFormat, supportedOutputTypes);

    if (isAudio) {
      decodeAudio = decodeOutput;
    } else {
      decodeVideo = decodeOutput;
    }

    if (addForcedAudioTrack) {
      sequenceAssetLoaderListener.onTrackAdded(
          FORCE_AUDIO_TRACK_FORMAT, SUPPORTED_OUTPUT_TYPE_DECODED);
    }

    return decodeOutput;
  }

  @Nullable
  @Override
  public SampleConsumer onOutputFormat(Format format) throws ExportException {
    @C.TrackType int trackType = getProcessedTrackType(format.sampleMimeType);
    SampleConsumer sampleConsumer;
    if (isCurrentAssetFirstAsset) {
      @Nullable
      SampleConsumer wrappedSampleConsumer = sequenceAssetLoaderListener.onOutputFormat(format);
      if (wrappedSampleConsumer == null) {
        return null;
      }
      sampleConsumer = new SampleConsumerWrapper(wrappedSampleConsumer);
      sampleConsumersByTrackType.put(trackType, sampleConsumer);

      if (forceAudioTrack && nonEndedTracks.get() == 1 && trackType == C.TRACK_TYPE_VIDEO) {
        SampleConsumer wrappedAudioSampleConsumer =
            checkStateNotNull(
                sequenceAssetLoaderListener.onOutputFormat(
                    FORCE_AUDIO_TRACK_FORMAT
                        .buildUpon()
                        .setSampleMimeType(MimeTypes.AUDIO_RAW)
                        .setPcmEncoding(C.ENCODING_PCM_16BIT)
                        .build()));
        sampleConsumersByTrackType.put(
            C.TRACK_TYPE_AUDIO, new SampleConsumerWrapper(wrappedAudioSampleConsumer));
      }
    } else {
      // TODO(b/270533049): Remove the check below when implementing blank video frames generation.
      boolean videoTrackDisappeared =
          nonEndedTracks.get() == 1
              && trackType == C.TRACK_TYPE_AUDIO
              && sampleConsumersByTrackType.size() == 2;
      checkState(
          !videoTrackDisappeared,
          "Inputs with no video track are not supported when the output contains a video track");
      sampleConsumer =
          checkStateNotNull(
              sampleConsumersByTrackType.get(trackType),
              "The preceding MediaItem does not contain any track of type " + trackType);
    }
    onMediaItemChanged(trackType, format);
    if (nonEndedTracks.get() == 1 && sampleConsumersByTrackType.size() == 2) {
      for (Map.Entry<Integer, SampleConsumer> entry : sampleConsumersByTrackType.entrySet()) {
        int outputTrackType = entry.getKey();
        if (trackType != outputTrackType) {
          onMediaItemChanged(outputTrackType, /* format= */ null);
        }
      }
    }
    return sampleConsumer;
  }

  private void onMediaItemChanged(int trackType, @Nullable Format format) {
    @Nullable
    OnMediaItemChangedListener onMediaItemChangedListener =
        mediaItemChangedListenersByTrackType.get(trackType);
    if (onMediaItemChangedListener == null) {
      return;
    }
    onMediaItemChangedListener.onMediaItemChanged(
        editedMediaItems.get(currentMediaItemIndex),
        currentAssetDurationUs,
        format,
        /* isLast= */ currentMediaItemIndex == editedMediaItems.size() - 1);
  }

  // Methods called from any thread.

  /**
   * Sets the maximum {@link EditedMediaItemSequence} duration in the {@link Composition}.
   *
   * <p>The duration passed is the current maximum duration. This method can be called multiple
   * times as this duration increases. Indeed, a sequence duration will increase during an export
   * when a new {@link MediaItem} is loaded, which can increase the maximum sequence duration.
   *
   * <p>Can be called from any thread.
   *
   * @param maxSequenceDurationUs The current maximum sequence duration, in microseconds.
   * @param isFinal Whether the duration passed is final. Setting this value to {@code true} means
   *     that the duration passed will not change anymore during the entire export.
   */
  public void setMaxSequenceDurationUs(long maxSequenceDurationUs, boolean isFinal) {
    this.maxSequenceDurationUs = maxSequenceDurationUs;
    isMaxSequenceDurationUsFinal = isFinal;
  }

  @Override
  public void onDurationUs(long durationUs) {
    checkArgument(
        durationUs != C.TIME_UNSET || currentMediaItemIndex == editedMediaItems.size() - 1,
        "Could not retrieve required duration for EditedMediaItem " + currentMediaItemIndex);
    currentAssetDurationUs = durationUs;
    if (editedMediaItems.size() == 1 && !isLooping) {
      sequenceAssetLoaderListener.onDurationUs(durationUs);
    }
  }

  @Override
  public void onTrackCount(int trackCount) {
    nonEndedTracks.set(trackCount);
  }

  @Override
  public void onError(ExportException exportException) {
    sequenceAssetLoaderListener.onError(exportException);
  }

  // Classes accessed from AssetLoader threads.

  private final class SampleConsumerWrapper implements SampleConsumer {

    private final SampleConsumer sampleConsumer;

    public SampleConsumerWrapper(SampleConsumer sampleConsumer) {
      this.sampleConsumer = sampleConsumer;
    }

    @Nullable
    @Override
    public DecoderInputBuffer getInputBuffer() {
      return sampleConsumer.getInputBuffer();
    }

    @Override
    public boolean queueInputBuffer() {
      DecoderInputBuffer inputBuffer = checkStateNotNull(sampleConsumer.getInputBuffer());
      long globalTimestampUs = totalDurationUs + inputBuffer.timeUs;
      if (isLooping && globalTimestampUs >= maxSequenceDurationUs) {
        if (isMaxSequenceDurationUsFinal && !audioLoopingEnded) {
          checkNotNull(inputBuffer.data).limit(0);
          inputBuffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM);
          // We know that queueInputBuffer() will always return true for the underlying
          // SampleConsumer so there is no need to handle the case where the sample wasn't queued.
          checkState(sampleConsumer.queueInputBuffer());
          audioLoopingEnded = true;
          if (nonEndedTracks.decrementAndGet() == 0) {
            release();
          }
        }
        return false;
      }

      if (inputBuffer.isEndOfStream()) {
        nonEndedTracks.decrementAndGet();
        if (currentMediaItemIndex < editedMediaItems.size() - 1 || isLooping) {
          inputBuffer.clear();
          inputBuffer.timeUs = 0;
          if (nonEndedTracks.get() == 0) {
            switchAssetLoader();
          }
        } else {
          checkState(sampleConsumer.queueInputBuffer());
          if (nonEndedTracks.get() == 0) {
            release();
          }
        }
        return true;
      }

      checkState(sampleConsumer.queueInputBuffer());
      return true;
    }

    // TODO(b/262693274): Test that concatenate 2 images or an image and a video works as expected
    //  once ImageAssetLoader implementation is complete.
    @Override
    public boolean queueInputBitmap(Bitmap inputBitmap, long durationUs, int frameRate) {
      if (isLooping && totalDurationUs + durationUs > maxSequenceDurationUs) {
        if (!isMaxSequenceDurationUsFinal) {
          return false;
        }
        durationUs = maxSequenceDurationUs - totalDurationUs;
        if (durationUs == 0) {
          if (!videoLoopingEnded) {
            videoLoopingEnded = true;
            signalEndOfVideoInput();
          }
          return false;
        }
        videoLoopingEnded = true;
      }

      return sampleConsumer.queueInputBitmap(inputBitmap, durationUs, frameRate);
    }

    @Override
    public void setOnInputFrameProcessedListener(OnInputFrameProcessedListener listener) {
      sampleConsumer.setOnInputFrameProcessedListener(listener);
    }

    @Override
    public boolean queueInputTexture(int texId, long presentationTimeUs) {
      long globalTimestampUs = totalDurationUs + presentationTimeUs;
      if (isLooping && globalTimestampUs >= maxSequenceDurationUs) {
        if (isMaxSequenceDurationUsFinal && !videoLoopingEnded) {
          videoLoopingEnded = true;
          signalEndOfVideoInput();
        }
        return false;
      }
      return sampleConsumer.queueInputTexture(texId, presentationTimeUs);
    }

    @Override
    public Surface getInputSurface() {
      return sampleConsumer.getInputSurface();
    }

    @Override
    public ColorInfo getExpectedInputColorInfo() {
      return sampleConsumer.getExpectedInputColorInfo();
    }

    @Override
    public int getPendingVideoFrameCount() {
      return sampleConsumer.getPendingVideoFrameCount();
    }

    @Override
    public boolean registerVideoFrame(long presentationTimeUs) {
      long globalTimestampUs = totalDurationUs + presentationTimeUs;
      if (isLooping && globalTimestampUs >= maxSequenceDurationUs) {
        if (isMaxSequenceDurationUsFinal && !videoLoopingEnded) {
          videoLoopingEnded = true;
          signalEndOfVideoInput();
        }
        return false;
      }

      return sampleConsumer.registerVideoFrame(presentationTimeUs);
    }

    @Override
    public void signalEndOfVideoInput() {
      boolean videoEnded =
          isLooping ? videoLoopingEnded : currentMediaItemIndex == editedMediaItems.size() - 1;
      if (videoEnded) {
        sampleConsumer.signalEndOfVideoInput();
      }
      if (nonEndedTracks.decrementAndGet() == 0) {
        if (videoEnded) {
          release();
        } else {
          switchAssetLoader();
        }
      }
    }

    private void switchAssetLoader() {
      handler.post(
          () -> {
            try {
              addCurrentProcessedInput();
              totalDurationUs += currentAssetDurationUs;
              currentAssetLoader.release();
              isCurrentAssetFirstAsset = false;
              currentMediaItemIndex++;
              if (currentMediaItemIndex == editedMediaItems.size()) {
                currentMediaItemIndex = 0;
                sequenceLoopCount++;
              }
              EditedMediaItem editedMediaItem = editedMediaItems.get(currentMediaItemIndex);
              currentAssetLoader =
                  assetLoaderFactory.createAssetLoader(
                      editedMediaItem,
                      checkNotNull(Looper.myLooper()),
                      /* listener= */ SequenceAssetLoader.this);
              currentAssetLoader.start();
            } catch (RuntimeException e) {
              onError(
                  ExportException.createForAssetLoader(e, ExportException.ERROR_CODE_UNSPECIFIED));
            }
          });
    }

    private void release() {
      // TODO(b/276415739): releasing the player earlier causes more release timeouts on emulator
      //  tests. Figure out what the cause is and uncomment the line below once fixed.
      // handler.post(SequenceAssetLoader.this::release);
    }
  }
}
