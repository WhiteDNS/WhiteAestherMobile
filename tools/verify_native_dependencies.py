#!/usr/bin/env python3
"""Check APK/AAB ELF architecture and dependency closure against Android's NDK API stubs."""

import argparse
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import zipfile


ABIS = {
    "armeabi-v7a": ("arm-linux-androideabi", 1, 40),
    "arm64-v8a": ("aarch64-linux-android", 2, 183),
    "x86_64": ("x86_64-linux-android", 2, 62),
}


def verify(artifact, toolchain, api):
    reader = toolchain / "bin" / ("llvm-readelf.exe" if (toolchain / "bin/llvm-readelf.exe").is_file() else "llvm-readelf")
    with zipfile.ZipFile(artifact) as archive, tempfile.TemporaryDirectory() as temporary:
        entries = {}
        for name in archive.namelist():
            match = re.fullmatch(r"(?:base/)?lib/([^/]+)/([^/]+\.so)", name)
            if match:
                abi, library = match.groups()
                if (abi, library) in entries:
                    raise ValueError(f"{artifact}: duplicate library {abi}/{library}")
                entries[abi, library] = name
        if not entries:
            raise ValueError(f"{artifact}: no native libraries found")
        for (abi, library), name in entries.items():
            triple, elf_class, machine = ABIS[abi]
            system_dir = toolchain / "sysroot/usr/lib" / triple / str(api)
            if not system_dir.is_dir():
                raise ValueError(f"Missing NDK API stubs: {system_dir}")
            contents = archive.read(name)
            if (len(contents) < 20 or contents[:4] != b"\x7fELF"
                    or contents[4] != elf_class or contents[5] != 1
                    or struct.unpack_from("<H", contents, 18)[0] != machine):
                raise ValueError(f"{artifact}: wrong ELF architecture for {name}")
            extracted = Path(temporary) / abi / library
            extracted.parent.mkdir(exist_ok=True)
            extracted.write_bytes(contents)
            dynamic = subprocess.run([str(reader), "--dynamic", str(extracted)],
                                     check=True, capture_output=True, text=True).stdout
            for dependency in re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", dynamic):
                if (abi, dependency) not in entries and not (system_dir / dependency).is_file():
                    raise ValueError(f"{artifact}: {name} requires missing {dependency}")
        print(f"{artifact.name}: {len(entries)} native libraries; architectures and dependencies verified")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--api", type=int, default=26)
    parser.add_argument("artifacts", nargs="+", type=Path)
    args = parser.parse_args()
    toolchains = list((args.ndk / "toolchains/llvm/prebuilt").iterdir())
    if len(toolchains) != 1:
        parser.error("Expected exactly one host toolchain in the NDK")
    for artifact in args.artifacts:
        verify(artifact, toolchains[0], args.api)


if __name__ == "__main__":
    main()
