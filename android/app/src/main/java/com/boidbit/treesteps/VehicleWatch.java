package com.boidbit.treesteps;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.DetectedActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps phone shake in a car, bus, train or on a bike from counting as steps. Google Play services tells us
 * when the phone gets into or out of a vehicle; the step counter is read at both moments and the steps in
 * between are left out (see StepStore.inVehicle). Phones without Play services simply count every step.
 */
public class VehicleWatch extends BroadcastReceiver {
    private static final int[] KINDS = { DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE };

    private static PendingIntent pending(Context c) {
        int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;   // Play services adds the result to it
        return PendingIntent.getBroadcast(c, 7, new Intent(c, VehicleWatch.class), PendingIntent.FLAG_UPDATE_CURRENT | mutable);
    }

    /** Starts (or, with the setting off or no permission, stops) listening for vehicle trips. */
    static void register(Context c) {
        try {
            if (!StepStore.ignoreVehicles(c) || !StepStore.useRealSteps(c) || !StepStore.hasPermission(c)) {
                ActivityRecognition.getClient(c).removeActivityTransitionUpdates(pending(c));
                StepStore.setInVehicle(c, false);
                return;
            }
            List<ActivityTransition> t = new ArrayList<>();
            for (int kind : KINDS) {
                t.add(new ActivityTransition.Builder().setActivityType(kind).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build());
                t.add(new ActivityTransition.Builder().setActivityType(kind).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build());
            }
            ActivityRecognition.getClient(c).requestActivityTransitionUpdates(new ActivityTransitionRequest(t), pending(c));
        } catch (RuntimeException ignored) {
            // no Play services, or permission missing: steps are counted as before
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return;
        ActivityTransitionResult result = ActivityTransitionResult.extractResult(intent);
        if (result == null || result.getTransitionEvents().isEmpty()) return;
        List<ActivityTransitionEvent> events = result.getTransitionEvents();
        boolean entering = events.get(events.size() - 1).getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_ENTER;
        Context c = context.getApplicationContext();
        PendingResult async = goAsync();
        // read the counter first: steps up to getting in still count, steps until getting out don't
        StepStore.readOnce(c, (steps, fresh) -> {
            StepStore.setInVehicle(c, entering);
            async.finish();
        });
    }
}
