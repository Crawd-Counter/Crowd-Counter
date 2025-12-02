# Object Counter - Real-Time Object Detection for Arm

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-blue.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Architecture-Arm-red.svg)](https://www.arm.com)

A native Android application that performs real-time object counting using computer vision, optimized for Arm-based mobile devices.

## 🎯 Project Overview

Object Counter is an on-device AI application that counts objects in real-time using your phone's camera or from photos in your gallery. Unlike cloud-based solutions, all processing happens locally on your Arm device, ensuring privacy, speed, and offline capability.

### Why This Project Stands Out

- **100% On-Device Processing**: No cloud dependencies, no data leaves your phone
- **Adaptive Background Detection**: Automatically identifies background color and detects objects that differ from it
- **Watershed Algorithm**: Accurately separates touching/overlapping objects
- **Real-Time Performance**: Optimized for Arm architecture with efficient OpenCV operations
- **Dual Input Modes**: Live camera detection and photo gallery analysis

## ✨ Features

| Feature | Description |
|---------|-------------|
| **Live Detection** | Real-time object counting through camera feed |
| **Photo Analysis** | Count objects from gallery images |
| **Smart Separation** | Watershed algorithm separates touching objects |
| **Adaptive Detection** | Auto-detects background color for accurate counting |
| **Visual Feedback** | Numbered bounding boxes overlay on detected objects |
| **Count Stabilization** | Smoothed counting reduces flickering in live mode |

## 🛠️ Technical Implementation

### Computer Vision Pipeline

1. **Color Space Conversion**: RGB → HSV for robust color analysis
2. **Background Detection**: Histogram-based dominant color identification
3. **Binary Masking**: Isolate objects from background
4. **Morphological Operations**: Noise reduction and gap filling
5. **Distance Transform**: Prepare for watershed segmentation
6. **Watershed Algorithm**: Separate touching objects
7. **Contour Analysis**: Extract bounding boxes and filter by size

### Arm Optimization

- Uses OpenCV's optimized native libraries for Arm
- Efficient memory management with proper Mat cleanup
- Frame skipping strategy for smooth real-time performance
- YUV to RGB conversion optimized for camera pipeline

## 📱 Requirements

- Android 9.0 (API 28) or higher
- Arm-based Android device (arm64-v8a, armeabi-v7a)
- Camera permission

## 🚀 Build Instructions

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34

### Step-by-Step Build

1. **Clone the repository**
   ```bash
   git clone https://github.com/Crawd-Counter/Crowd-Counter.git
   cd crowd-counter
   ```

2. **Open in Android Studio**
   - Launch Android Studio Hedgehog (2023.1.1) or newer
   - Select "Open an existing project"
   - Navigate to the cloned directory

3. **Sync Gradle**
   - Android Studio will automatically prompt to sync
   - Click "Sync Now" when prompted
   - Wait for dependencies to download (includes OpenCV)

4. **Build the APK**
   - Use Android Studio: Build → Build Bundle(s) / APK(s) → Build APK(s)
   - Or via terminal (after Android Studio generates wrapper):
     ```bash
     ./gradlew assembleDebug
     ```

5. **Install on Device**
   - Connect your Arm-based Android device via USB
   - Enable USB debugging in Developer Options
   - Click Run (▶️) in Android Studio
   
   Or via command line:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Running the App

1. Launch "Object Counter" from your app drawer
2. Grant camera permission when prompted
3. **Live Mode**: Tap "Detect" to start counting objects through camera
4. **Photo Mode**: Tap "Photo" to select an image from gallery
5. Tap "Reset" to return to camera view

## 📂 Project Structure

```
object-counter/
├── app/
│   ├── src/main/
│   │   ├── java/com/objectcounter/
│   │   │   ├── MainActivity.kt          # Main UI and camera handling
│   │   │   ├── ml/
│   │   │   │   └── ObjectDetector.kt    # Core detection algorithm
│   │   │   └── ui/
│   │   │       └── OverlayView.kt       # Detection visualization
│   │   ├── res/
│   │   │   ├── layout/                  # UI layouts
│   │   │   └── values/                  # Colors, themes
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
├── LICENSE
└── README.md
```

## 🔧 Dependencies

- **CameraX 1.3.1**: Modern camera API for Android
- **OpenCV 4.9.0**: Computer vision library
- **AndroidX**: Core Android components
- **Material Design**: UI components

## 🎨 Use Cases

- **Inventory Management**: Count products, parts, or items
- **Education**: Count objects for learning activities
- **Quality Control**: Verify item counts in manufacturing
- **Agriculture**: Count seeds, fruits, or produce
- **Research**: Count specimens or samples

## 🏆 Hackathon Submission

This project was built for the **Arm AI Developer Challenge 2025**, demonstrating:

- ✅ AI/ML running locally on Arm architecture
- ✅ Innovative use of computer vision for practical problem-solving
- ✅ Production-ready code quality
- ✅ Optimized performance for mobile devices
- ✅ Open source with MIT license

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

---

Built with ❤️ for the Arm AI Developer Challenge 2025
