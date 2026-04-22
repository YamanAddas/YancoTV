# Native build notes — FFmpeg extension (MK.9)

The FFmpeg ExoPlayer extension is vendored into the app module — not pulled from Maven,
because Google does not publish `media3-decoder-ffmpeg` (licensing / GPL-vs-LGPL risk).

## What's checked in

- `app/src/main/java/androidx/media3/decoder/ffmpeg/*.java` — extension Java sources
  copied verbatim from `androidx/media` at tag `1.5.1`. Kept in the upstream package
  so `DefaultRenderersFactory`'s reflection-based loader finds them.
- `app/src/main/jniLibs/<abi>/libffmpegJNI.so` — prebuilt JNI shim linked against
  static FFmpeg libs. Stripped with `llvm-strip --strip-unneeded`.

## What's NOT checked in

- FFmpeg source tree (100+ MB)
- FFmpeg static archives (`libavcodec.a`, `libswresample.a`, `libavutil.a` — ~125 MB across 4 ABIs)
- Android NDK r26d (~2 GB unpacked)
- CMakeLists.txt / ffmpeg_jni.cc (upstream, not modified)

## Rebuilding `libffmpegJNI.so` from scratch

Prerequisites: WSL2 Ubuntu with passwordless sudo.

```bash
# 1. One-time apt deps
sudo apt-get install -y nasm yasm cmake pkg-config build-essential \
    autoconf automake libtool wget unzip ninja-build

# 2. Workspace
mkdir -p ~/media3-ffmpeg-build && cd ~/media3-ffmpeg-build

# 3. NDK r26d (matches what the Media3 1.5.1 extension README recommends)
wget https://dl.google.com/android/repository/android-ndk-r26d-linux.zip
unzip -q android-ndk-r26d-linux.zip

# 4. androidx/media at tag 1.5.1 (shallow)
git clone --depth 1 --branch 1.5.1 https://github.com/androidx/media.git

# 5. FFmpeg 6.0 into the extension jni dir
cd media/libraries/decoder_ffmpeg/src/main/jni
git clone --depth 1 --branch release/6.0 https://github.com/FFmpeg/FFmpeg.git ffmpeg

# 6. Build FFmpeg static libs for all 4 ABIs
export FFMPEG_MODULE_PATH=~/media3-ffmpeg-build/media/libraries/decoder_ffmpeg/src/main
export NDK_PATH=~/media3-ffmpeg-build/android-ndk-r26d
ENABLED_DECODERS=(
  ac3 eac3 mp3 mp3float aac aac_latm alac flac dca truehd mlp opus vorbis
  pcm_alaw pcm_mulaw pcm_s16le pcm_s16be pcm_s24le pcm_s24be
  pcm_s32le pcm_s32be pcm_f32le pcm_f32be
  hevc h264 mpeg2video mpeg4 vp8 vp9
)
./build_ffmpeg.sh "${FFMPEG_MODULE_PATH}" "${NDK_PATH}" linux-x86_64 24 "${ENABLED_DECODERS[@]}"

# 7. Build libffmpegJNI.so per ABI via NDK's CMake toolchain
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
  cmake -S "${FFMPEG_MODULE_PATH}/jni" -B /tmp/jni-$abi \
    -DCMAKE_TOOLCHAIN_FILE=$NDK_PATH/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=$abi -DANDROID_PLATFORM=android-24 \
    -DCMAKE_BUILD_TYPE=Release -GNinja
  cmake --build /tmp/jni-$abi
  $NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip \
    --strip-unneeded /tmp/jni-$abi/libffmpegJNI.so
done

# 8. Copy into the app module (run from WSL; /mnt/d/ is the Windows D: drive)
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
  cp /tmp/jni-$abi/libffmpegJNI.so \
     /mnt/d/YancoTV/packages/android/app/src/main/jniLibs/$abi/
done
```

On a 16-core machine the full pipeline takes ~10 minutes. Step 6 dominates.

## Decoder set rationale

- Audio (production `FfmpegAudioRenderer`): AC3/EAC3/DTS/TrueHD/MLP fix the Fire TV
  audio codec gap (MB-14). MP3/AAC/FLAC/ALAC/Opus/Vorbis/PCM cover VOD containers.
- Video (experimental `ExperimentalFfmpegVideoRenderer`): HEVC/H264/MPEG2/MPEG4/VP8/VP9.
  The renderer is labelled experimental upstream — software decode, not suitable for
  4K HEVC on Fire TV Stick class hardware. Included for lower-resolution fallback only.

## How registration works

`PlaybackService.onCreate` builds the ExoPlayer with:

```kotlin
DefaultRenderersFactory(this)
    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    .setEnableDecoderFallback(true)
```

The factory scans the classpath via `Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")`.
When found, it inserts the renderer ahead of `MediaCodecAudioRenderer` — so FFmpeg handles
any format both can decode. If FFmpeg can't handle a format, MediaCodec is the fallback
(guaranteed by `enableDecoderFallback=true`). No other app code needs to know about the extension.

## Upgrading Media3

When bumping `media3` in `gradle/libs.versions.toml`:

1. Re-clone `androidx/media` at the new tag.
2. Re-copy `libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/*.java`
   into `app/src/main/java/androidx/media3/decoder/ffmpeg/`.
3. Check the new README for FFmpeg version / NDK version changes. If either moved,
   rebuild `libffmpegJNI.so` following the steps above.
4. Otherwise `libffmpegJNI.so` is typically stable across Media3 patch releases —
   the JNI ABI rarely changes.
