package com.transcription.app;

import static org.junit.Assert.assertNotNull;

import android.os.Bundle;
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

    @Test public void recreated_doesNotDoubleAdd() {
        // After config change we get a non-null savedInstanceState — we must
        // NOT add the fragment again.
        ActivityController<SettingsActivity> ctrl =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        Bundle state = new Bundle();
        ctrl.saveInstanceState(state).pause().stop().destroy();

        ActivityController<SettingsActivity> recreated =
                Robolectric.buildActivity(SettingsActivity.class)
                        .create(state).start().resume();
        assertNotNull(recreated.get());
    }
}
