#!/bin/sh

# Use Gradle for build with dependencies
echo "Building with Gradle (includes JInput gamepad support)..."
gradle clean build

if [ $? -eq 0 ]; then
    echo "Build successful!"
    echo "Run..."
# Use the fat JAR created by Gradle which includes all dependencies
# Add system properties for proper JInput native library loading
# Enable fine-grained logging for gamepad debugging
java -Dsun.java2d.opengl=true \
     --enable-native-access=ALL-UNNAMED \
     -Djava.library.path=$(pwd)/natives \
     -Djava.util.logging.level=INFO \
     -Djava.util.logging.ConsoleHandler.level=FINE \
     -Dawt.RealGamepadController.level=FINE \
     -jar build/libs/mochadoom.jar "$@"
else
    echo "Build failed!"
    exit 1
fi

