param(
    [Parameter(Mandatory=$true)][string]$FliteSource,
    [string]$SdkRoot = "$env:LOCALAPPDATA/Android/Sdk"
)
$ErrorActionPreference = "Stop"
$project = (Resolve-Path "$PSScriptRoot/../..").Path
$source = (Resolve-Path $FliteSource).Path
$ndk = "$SdkRoot/ndk/28.2.13676358"
$bin = "$SdkRoot/cmake/3.22.1/bin"
foreach ($abi in @("arm64-v8a", "armeabi-v7a", "x86_64")) {
    $build = "$project/build/voice/$abi"
    & "$bin/cmake.exe" -S $PSScriptRoot -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$bin/ninja.exe" "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake" "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-26 "-DFLITE_SOURCE=$source" -DCMAKE_BUILD_TYPE=MinSizeRel
    if ($LASTEXITCODE -ne 0) { throw "Configuration failed for $abi" }
    & "$bin/cmake.exe" --build $build --parallel 4
    if ($LASTEXITCODE -ne 0) { throw "Compilation failed for $abi" }
    $destination = "$project/app/src/main/jniLibs/$abi"
    New-Item -ItemType Directory -Force $destination | Out-Null
    & "$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" -o "$destination/libcompanion_voice.so" "$build/libcompanion_voice.so"
    if ($LASTEXITCODE -ne 0) { throw "Packaging failed for $abi" }
}
