# Hum to Music AI – AI Arranger

Android app for recording a hummed melody, detecting notes on-device, selecting an arrangement style, and exporting the recorded WAV.

## Current version

**1.0.1** – stability and functional export update.

### Fixed / improved
- Fixed the missing `viewModelScope` import that prevented Kotlin compilation.
- Recording shutdown now finalizes the WAV header instead of cancelling the writer before it can finish.
- Microphone permission is checked when Record is pressed.
- WAV export now opens Android's file picker and copies the recorded file.
- MIDI and chord-sheet buttons are visibly disabled until their engines are implemented.
- Added GitHub Actions to build a debug APK automatically.

## Package

`com.amjrd.humtomusic`
