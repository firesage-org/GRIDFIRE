#!/usr/bin/env bash

if [ -z "$GRAALVM_HOME" ]; then
    echo "Please set \$GRAALVM_HOME"
    exit 1
fi

if [ ! -e "$GRAALVM_HOME/bin/native-image" ]; then
    echo "Installing the native-image command..."
    "$GRAALVM_HOME/bin/gu" install native-image
fi

echo "Compiling Clojure to Java bytecode..."
clojure -M -e "(compile 'gridfire.gen-raster)"

"$GRAALVM_HOME/bin/native-image" \
    -cp "$(clojure -Spath)":classes \
    -H:Name=gridfire-native-image \
    -H:+ReportExceptionStackTraces \
    --verbose \
    --no-fallback \
    --allow-incomplete-classpath \
    --initialize-at-build-time="" \
    --diagnostics-mode \
    --initialize-at-run-time=com.sun.media.imageioimpl.plugins.tiff.TIFFCodecLibFaxDecompressor \
    --initialize-at-run-time=com.sun.media.imageioimpl.plugins.pnm.PNMImageReader \
    --enable-all-security-services \
    --trace-object-instantiation=java.util.TimerThread \
    --trace-object-instantiation=java.io.RandomAccessFile \
    --trace-object-instantiation=org.geotools.util.WeakCollectionCleaner \
    gridfire.gen_raster

    # "-J-Dclojure.compiler.direct-linking=true" \
    # --no-server
    # --report-unsupported-elements-at-runtime \
    # "-J-Xmx100G" \
    # -H:ConfigurationFileDirectories=. \
