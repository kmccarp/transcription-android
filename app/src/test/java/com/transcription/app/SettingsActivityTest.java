package com.transcription.app;

import static org.junit.Assert.assertNotNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;

@RunWith(AndroidJUnit4.class)
public class SettingsActivityTest {

    @Test public void launches_andHostsPreferenceFragment() {
        ActivityController<SettingsActivity> ctrl =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = ctrl.get();
        assertNotNull(activity.getSupportFragmentManager()
                .findFragmentById(android.R.id.content));
    }
}
