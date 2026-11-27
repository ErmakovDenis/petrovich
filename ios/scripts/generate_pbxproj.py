#!/usr/bin/env python3
"""Минимальный Petrovich.xcodeproj без XcodeGen (для репозитория на Linux).
На Mac предпочтительно: `xcodegen generate` из ios/project.yml.
"""
from __future__ import annotations

import os
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "Petrovich"
OUT = ROOT / "Petrovich.xcodeproj" / "project.pbxproj"


def uid() -> str:
    return uuid.uuid4().hex[:24].upper()


def main() -> None:
    files = sorted(
        p for p in SRC.rglob("*")
        if p.is_file() and p.suffix in {".swift", ".plist", ".json"}
        and "AppIcon" not in p.parts or p.name == "Contents.json" and "Assets" in str(p)
    )
    # Collect swift + plist + asset catalog root
    swift = sorted(SRC.rglob("*.swift"))
    plist = SRC / "Resources" / "Info.plist"
    assets = SRC / "Resources" / "Assets.xcassets"

    project_id = uid()
    target_id = uid()
    sources_phase = uid()
    resources_phase = uid()
    frameworks_phase = uid()
    native_target = target_id
    product_ref = uid()
    main_group = uid()
    products_group = uid()
    src_group = uid()
    build_config_list_project = uid()
    build_config_list_target = uid()
    debug_project = uid()
    release_project = uid()
    debug_target = uid()
    release_target = uid()

    file_entries: list[tuple[str, Path, str]] = []  # id, path, build_id
    for path in swift:
        file_entries.append((uid(), path, uid()))
    plist_id, plist_build = uid(), uid()
    assets_id, assets_build = uid(), uid()

    lines: list[str] = []
    w = lines.append
    w("// !$*UTF8*$!")
    w("{")
    w("\tarchiveVersion = 1;")
    w("\tclasses = {};")
    w("\tobjectVersion = 56;")
    w("\tobjects = {")

    # PBXBuildFile
    for fid, path, bid in file_entries:
        w(f"\t\t{bid} /* {path.name} in Sources */ = {{isa = PBXBuildFile; fileRef = {fid} /* {path.name} */; }};")
    w(f"\t\t{plist_build} /* Info.plist in Resources */ = {{isa = PBXBuildFile; fileRef = {plist_id} /* Info.plist */; }};")
    w(f"\t\t{assets_build} /* Assets.xcassets in Resources */ = {{isa = PBXBuildFile; fileRef = {assets_id} /* Assets.xcassets */; }};")

    # PBXFileReference
    w(f"\t\t{product_ref} /* Petrovich.app */ = {{isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = Petrovich.app; sourceTree = BUILT_PRODUCTS_DIR; }};")
    for fid, path, _ in file_entries:
        rel = path.relative_to(ROOT).as_posix()
        w(f"\t\t{fid} /* {path.name} */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = {path.name}; sourceTree = \"<group>\"; }};")
    w(f"\t\t{plist_id} /* Info.plist */ = {{isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = Info.plist; sourceTree = \"<group>\"; }};")
    w(f"\t\t{assets_id} /* Assets.xcassets */ = {{isa = PBXFileReference; lastKnownFileType = folder.assetcatalog; path = Assets.xcassets; sourceTree = \"<group>\"; }};")

    # Groups — flat Sources for simplicity; paths via name only (files must live correctly on disk for XcodeGen; this pbxproj is a bootstrap)
    # Better approach: put all file refs with full relative path from group
    # Rebuild with path = relative to Petrovich group using nested structure is complex.
    # Use path to file relative to SRC for each file ref:

    # Actually fix file refs to use full path from project:
    # Simpler: one group Petrovich with children using path relative to ios/

    w(f"\t\t{products_group} = {{isa = PBXGroup; children = ({product_ref} /* Petrovich.app */); name = Products; sourceTree = \"<group>\"; }};")

    # Recreate file refs properly
    # Clear approach: regenerate entire objects section more carefully via second pass
    print("Generating flat source list…")

    # Groups with all swift files as children of src_group; file refs use path relative to SRC
    children = " ".join(f"{fid} /* {path.name} */," for fid, path, _ in file_entries)
    w(
        f"\t\t{src_group} = {{\n"
        f"\t\t\tisa = PBXGroup;\n"
        f"\t\t\tchildren = (\n"
        + "".join(f"\t\t\t\t{fid} /* {path.name} */,\n" for fid, path, _ in file_entries)
        + f"\t\t\t\t{plist_id} /* Info.plist */,\n"
        + f"\t\t\t\t{assets_id} /* Assets.xcassets */,\n"
        + f"\t\t\t);\n"
        f"\t\t\tpath = Petrovich;\n"
        f"\t\t\tsourceTree = \"<group>\";\n"
        f"\t\t}};"
    )
    # Fix paths for nested files: Xcode needs either nested groups or path with subdirs.
    # Update file references to include subdirectory path from Petrovich/
    # We'll rewrite file refs below by patching - actually already emitted wrong.
    # Skip: document that xcodegen is the source of truth.

    w(
        f"\t\t{main_group} = {{\n"
        f"\t\t\tisa = PBXGroup;\n"
        f"\t\t\tchildren = ({src_group} /* Petrovich */, {products_group} /* Products */);\n"
        f"\t\t\tsourceTree = \"<group>\";\n"
        f"\t\t}};"
    )

    # Sources build phase
    w(f"\t\t{sources_phase} = {{isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = (")
    for _, path, bid in file_entries:
        w(f"\t\t\t{bid} /* {path.name} in Sources */,")
    w("\t\t); runOnlyForDeploymentPostprocessing = 0; };")

    w(f"\t\t{resources_phase} = {{isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = (")
    w(f"\t\t\t{assets_build} /* Assets.xcassets in Resources */,")
    w("\t\t); runOnlyForDeploymentPostprocessing = 0; };")

    w(f"\t\t{frameworks_phase} = {{isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; }};")

    w(
        f"\t\t{native_target} = {{\n"
        f"\t\t\tisa = PBXNativeTarget;\n"
        f"\t\t\tbuildConfigurationList = {build_config_list_target};\n"
        f"\t\t\tbuildPhases = ({sources_phase}, {frameworks_phase}, {resources_phase});\n"
        f"\t\t\tbuildRules = ();\n"
        f"\t\t\tdependencies = ();\n"
        f"\t\t\tname = Petrovich;\n"
        f"\t\t\tproductName = Petrovich;\n"
        f"\t\t\tproductReference = {product_ref};\n"
        f"\t\t\tproductType = \"com.apple.product-type.application\";\n"
        f"\t\t}};"
    )

    w(
        f"\t\t{project_id} = {{\n"
        f"\t\t\tisa = PBXProject;\n"
        f"\t\t\tattributes = {{ LastSwiftUpdateCheck = 1500; LastUpgradeCheck = 1500; }};\n"
        f"\t\t\tbuildConfigurationList = {build_config_list_project};\n"
        f"\t\t\tcompatibilityVersion = \"Xcode 14.0\";\n"
        f"\t\t\tdevelopmentRegion = ru;\n"
        f"\t\t\thasScannedForEncodings = 0;\n"
        f"\t\t\tknownRegions = (ru, en, Base);\n"
        f"\t\t\tmainGroup = {main_group};\n"
        f"\t\t\tproductRefGroup = {products_group};\n"
        f"\t\t\tprojectDirPath = \"\";\n"
        f"\t\t\tprojectRoot = \"\";\n"
        f"\t\t\ttargets = ({native_target});\n"
        f"\t\t}};"
    )

    def cfg(cid: str, name: str, target: bool) -> None:
        w(f"\t\t{cid} = {{isa = XCBuildConfiguration; buildSettings = {{")
        if not target:
            w("\t\t\tALWAYS_SEARCH_USER_PATHS = NO;")
            w("\t\t\tCLANG_ENABLE_MODULES = YES;")
            w("\t\t\tIPHONEOS_DEPLOYMENT_TARGET = 17.0;")
            w("\t\t\tSDKROOT = iphoneos;")
            w("\t\t\tSWIFT_VERSION = 5.9;")
        else:
            w("\t\t\tASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;")
            w("\t\t\tCODE_SIGN_STYLE = Automatic;")
            w("\t\t\tCURRENT_PROJECT_VERSION = 1;")
            w("\t\t\tGENERATE_INFOPLIST_FILE = NO;")
            w("\t\t\tINFOPLIST_FILE = Petrovich/Resources/Info.plist;")
            w("\t\t\tLD_RUNPATH_SEARCH_PATHS = \"$(inherited) @executable_path/Frameworks\";")
            w("\t\t\tMARKETING_VERSION = 0.1.0;")
            w("\t\t\tPRODUCT_BUNDLE_IDENTIFIER = ru.petrovich.telemetry;")
            w("\t\t\tPRODUCT_NAME = \"$(TARGET_NAME)\";")
            w("\t\t\tSWIFT_EMIT_LOC_STRINGS = YES;")
            w("\t\t\tSWIFT_VERSION = 5.9;")
            w("\t\t\tTARGETED_DEVICE_FAMILY = \"1,2\";")
            w("\t\t\tSTAND_URL = \"https://petrovichai.ru\";")
            w("\t\t\tAUTOGRAPH_BASE_URL = \"https://web.tk-ekat.ru/ServiceJSON/\";")
        w(f"\t\t}}; name = {name}; }};")

    cfg(debug_project, "Debug", False)
    cfg(release_project, "Release", False)
    cfg(debug_target, "Debug", True)
    cfg(release_target, "Release", True)

    w(f"\t\t{build_config_list_project} = {{isa = XCConfigurationList; buildConfigurations = ({debug_project}, {release_project}); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; }};")
    w(f"\t\t{build_config_list_target} = {{isa = XCConfigurationList; buildConfigurations = ({debug_target}, {release_target}); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; }};")

    w("\t};")
    w(f"\trootObject = {project_id} /* Project object */;")
    w("}")

    # Rewrite with correct relative paths for nested swift files
    # Patch file reference lines: path should be relative to Petrovich group
    text = "\n".join(lines)
    for fid, path, _ in file_entries:
        rel = path.relative_to(SRC).as_posix()
        old = f"path = {path.name}; sourceTree = \"<group>\";"
        # only replace the occurrence for this file id block — approximate:
        text = text.replace(
            f"{fid} /* {path.name} */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = {path.name}; sourceTree = \"<group>\"; }};",
            f"{fid} /* {path.name} */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = {rel}; sourceTree = \"<group>\"; }};",
        )
    text = text.replace(
        f"{plist_id} /* Info.plist */ = {{isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = Info.plist; sourceTree = \"<group>\"; }};",
        f"{plist_id} /* Info.plist */ = {{isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = Resources/Info.plist; sourceTree = \"<group>\"; }};",
    )
    text = text.replace(
        f"{assets_id} /* Assets.xcassets */ = {{isa = PBXFileReference; lastKnownFileType = folder.assetcatalog; path = Assets.xcassets; sourceTree = \"<group>\"; }};",
        f"{assets_id} /* Assets.xcassets */ = {{isa = PBXFileReference; lastKnownFileType = folder.assetcatalog; path = Resources/Assets.xcassets; sourceTree = \"<group>\"; }};",
    )

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text + "\n")
    print(f"Wrote {OUT} ({len(swift)} swift files)")


if __name__ == "__main__":
    main()
