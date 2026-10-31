package com.boidbit.treesteps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fires the daily reminder. After a reboot or an app update (both clear alarms and activity updates) it sets
 * the alarm again and restarts vehicle detection.
 */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Context c = context.getApplicationContext();
        if (Reminders.ACTION.equals(intent.getAction())) {
            PendingResult pending = goAsync();
            StepStore.readOnce(c, (steps, fresh) -> {
                Reminders.notifyIfBehind(c, steps);
                pending.finish();
            });
        } else {
            Reminders.schedule(c);
            StepStore.setInVehicle(c, false);
            VehicleWatch.register(c);
        }
    }
}
