#!/bin/bash
# Script to build iOS libraries for Flutter plugin
# Run from the repository root: ./scripts/build_xcframeworks.sh

set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTPUT_DIR="${REPO_ROOT}/build/ios"
DERIVED_DATA="${REPO_ROOT}/build/DerivedData"

echo "Building iOS libraries..."
echo "Repository root: ${REPO_ROOT}"
echo "Output directory: ${OUTPUT_DIR}"

# Clean previous builds
rm -rf "${OUTPUT_DIR}"
rm -rf "${DERIVED_DATA}"
mkdir -p "${OUTPUT_DIR}"

cd "${REPO_ROOT}"

# Build for iOS device (arm64) - Release without BUILD_LIBRARY_FOR_DISTRIBUTION
echo "Building for iOS device..."
xcodebuild build \
    -scheme SpruceIDMobileSdk \
    -destination "generic/platform=iOS" \
    -derivedDataPath "${DERIVED_DATA}" \
    -configuration Release

# Find the built products
IOS_BUILD="${DERIVED_DATA}/Build/Products/Release-iphoneos"

echo "iOS build products: ${IOS_BUILD}"
echo ""
echo "Listing build products:"
ls -la "${IOS_BUILD}/" 2>/dev/null || echo "iOS build dir not found"

# Copy swiftmodule files
echo ""
echo "Copying Swift modules..."
mkdir -p "${OUTPUT_DIR}/modules"

# Copy static libraries
if ls "${IOS_BUILD}"/*.a 1> /dev/null 2>&1; then
    cp "${IOS_BUILD}"/*.a "${OUTPUT_DIR}/"
    echo "Copied static libraries"
fi

# Copy .o files (object files)
if ls "${IOS_BUILD}"/*.o 1> /dev/null 2>&1; then
    cp "${IOS_BUILD}"/*.o "${OUTPUT_DIR}/"
    echo "Copied object files"
fi

# Copy swiftmodule directories
for module in SpruceIDMobileSdk SpruceIDMobileSdkRs Algorithms RealModule; do
    if [ -d "${IOS_BUILD}/${module}.swiftmodule" ]; then
        cp -R "${IOS_BUILD}/${module}.swiftmodule" "${OUTPUT_DIR}/modules/"
        echo "Copied ${module}.swiftmodule"
    fi
done

# Copy RustFramework (already exists)
echo ""
echo "Copying RustFramework.xcframework..."
cp -R "${REPO_ROOT}/rust/MobileSdkRs/RustFramework.xcframework" "${OUTPUT_DIR}/"

# ============================================
# Package frameworks for Flutter plugin
# ============================================
echo ""
echo "Packaging frameworks for Flutter plugin..."
FLUTTER_FRAMEWORKS="${REPO_ROOT}/flutter/ios/Frameworks"

rm -rf "${FLUTTER_FRAMEWORKS}"
mkdir -p "${FLUTTER_FRAMEWORKS}"

# Copy RustFramework.xcframework
cp -R "${OUTPUT_DIR}/RustFramework.xcframework" "${FLUTTER_FRAMEWORKS}/"
echo "Copied RustFramework.xcframework"

# Copy swiftmodule files for import
mkdir -p "${FLUTTER_FRAMEWORKS}/SwiftModules"
cp -R "${OUTPUT_DIR}/modules/SpruceIDMobileSdk.swiftmodule" "${FLUTTER_FRAMEWORKS}/SwiftModules/"
cp -R "${OUTPUT_DIR}/modules/SpruceIDMobileSdkRs.swiftmodule" "${FLUTTER_FRAMEWORKS}/SwiftModules/"
cp -R "${OUTPUT_DIR}/modules/Algorithms.swiftmodule" "${FLUTTER_FRAMEWORKS}/SwiftModules/"
cp -R "${OUTPUT_DIR}/modules/RealModule.swiftmodule" "${FLUTTER_FRAMEWORKS}/SwiftModules/"
echo "Copied Swift modules"

# Create a static library from object files
OBJECTS="${OUTPUT_DIR}/SpruceIDMobileSdk.o ${OUTPUT_DIR}/SpruceIDMobileSdkRs.o ${OUTPUT_DIR}/Algorithms.o ${OUTPUT_DIR}/RealModule.o ${OUTPUT_DIR}/_NumericsShims.o"
ar rcs "${FLUTTER_FRAMEWORKS}/libSpruceIDMobileSdk.a" ${OBJECTS}
echo "Created libSpruceIDMobileSdk.a"

echo ""
echo "Build and packaging complete!"
echo ""
echo "Files for Flutter plugin:"
ls -la "${FLUTTER_FRAMEWORKS}" 2>/dev/null || echo "No output files found"

# Clean up intermediate build files (saves ~4GB)
echo ""
echo "Cleaning up intermediate build files..."
rm -rf "${REPO_ROOT}/build"
echo "Done!"
