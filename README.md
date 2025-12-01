# Object Counter - Native Kotlin with OpenCV

Native Android app for real-time object counting using OpenCV computer vision techniques.

## Features

- **Contour Detection**: Detects objects based on shape outlines
- **Blob Detection**: Finds circular/blob-like objects with circularity analysis
- **Color Detection**: Detects objects by color (default: red objects)
- **Edge Detection**: Uses Canny edge detection to find object boundaries

## Why OpenCV over AI Models?

- More accurate counting of small or densely packed objects
- No model files needed - works out of the box
- Faster processing for simple counting tasks
- Deterministic results - same input always gives same output
- Lower memory footprint

## Setup

1. Open in Android Studio and sync Gradle
2. Build and run on device
3. Select detection mode using the mode button

## Detection Modes

| Mode | Best For |
|------|----------|
| Contour | General object counting, distinct shapes |
| Blob | Circular objects, pills, coins |
| Color | Objects of specific color |
| Edge | Objects with clear boundaries |

## Requirements

- Android SDK 28+ (Android 9.0)
- Device with camera
