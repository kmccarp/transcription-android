/**
 * Core, Android-free transcription primitives.
 *
 * <p>Everything that does not depend on the Android framework lives here so it
 * can be unit-tested on a plain JVM (and so the {@code :core} module compiles
 * without an Android SDK installed). The Android {@code :app} module is a thin
 * UI shell over this package.
 */
package com.transcription.core;
