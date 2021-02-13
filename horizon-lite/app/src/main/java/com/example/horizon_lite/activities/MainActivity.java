package com.example.horizon_lite.activities;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.room.Room;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.horizon_lite.room.Converters;
import com.example.horizon_lite.R;
import com.example.horizon_lite.Task;
import com.example.horizon_lite.room.TaskDatabase;
import com.example.horizon_lite.recyclerViews.TasksAdapter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.time.temporal.ChronoUnit.DAYS;


public class MainActivity extends AppCompatActivity {

    //private fields for the Dao and the Database
    public static TaskDatabase taskDatabase;
    RecyclerView rvTasks;
    TasksAdapter adapter;
    SharedPreferences sharedPreferences;

    private final int LIST_TASK_REQUEST = 1;

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate( SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE tasks "
                    + " ADD COLUMN late INTEGER");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //streak
        sharedPreferences = getPreferences(Context.MODE_PRIVATE);

        //initialise the database
        taskDatabase = Room.databaseBuilder(getApplicationContext(),
                TaskDatabase.class, "tasks")
                .fallbackToDestructiveMigration()
                .build();

        // Lookup the recyclerview in activity layout
        rvTasks = (RecyclerView) findViewById(R.id.rvTasks);

        //ROOM Threads
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            //Background work here
            //NB: This is the new thread in which the database stuff happens
            List<Task> tasks = taskDatabase.taskDao().getAllUncompletedTasks();
            Collections.reverse(tasks);
            for (int i = 0; i < tasks.size(); i++) {
                if (DAYS.between(tasks.get(i).getDate(), LocalDate.now()) != 0) {
                    Task task = tasks.get(i);
                    tasks.remove(i);
                    tasks.add(0, task);
                }
            }
            handler.post(() -> {
                // Create adapter passing in the sample user data
                adapter = new TasksAdapter(tasks);
                // Attach the adapter to the recyclerview to populate items
                rvTasks.setAdapter(adapter);
                // Set layout manager to position the items
                rvTasks.setLayoutManager(new LinearLayoutManager(this));
                // That's all!
            });
        });
        updateStreak();
    }

    /**
     * When the view is resumed
     */
    public void onResume() {
        super.onResume();
        updateStreak();
    }


    /**
     * When the archive button is pressed
     * @param view
     */
    public void onArchiveButton( View view) {
        Intent intent = new Intent(this, ArchiveActivity.class);
        adapter.removeAllChecked();
        startActivityForResult(intent, LIST_TASK_REQUEST);
    }


    /**
     * When there is a result from an activity
     * @param requestCode the requestCode
     * @param resultCode the resultCode
     * @param data the data from the activity
     */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LIST_TASK_REQUEST) {
            if(resultCode == RESULT_OK) {
                String[] names = data.getStringArrayExtra("names");
                ArrayList<String> namesAL = new ArrayList<String>();
                Collections.addAll(namesAL, names);
                addTasks(namesAL);
//                for (String name : names) {
//                    addTask(name);
//                }
            }
        }
    }

    /*
     * Update the streak
     */
    public void updateStreak() {
        //ROOM Threads
        final SharedPreferences.Editor editor = sharedPreferences.edit();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            int streak = sharedPreferences.getInt(getString(R.string.streak), 0);

            //calculate the maximum streak
            int maxStreak = -1;
            Task lastStreakTask = taskDatabase.taskDao().getLastStreakTask(Converters.dateToTimestamp(LocalDate.now()));
            if (lastStreakTask != null) { //if there was a previous failed task
                //the streak is the difference between the day after that day and today
                maxStreak = (int)DAYS.between(lastStreakTask.getDate(), LocalDate.now())-1;
            } else { //if there were no previous failed tasks
                Task firstEverTask = taskDatabase.taskDao().getFirstEverTask();
                //if there is a first task
                if (firstEverTask != null) { //day difference between the first ever (completed) task and today is the streak
                    maxStreak = (int)DAYS.between(firstEverTask.getDate(), LocalDate.now());
                } else {  //if there are no tasks at all ever
                    maxStreak = 0; //if there are no tasks at all ever, then the streak is 0
                }
            }

            //if the streak hasn't been updated today
            LocalDate lastUpdated = Converters.fromTimestamp(sharedPreferences.getString(getString(R.string.last_updated), Converters.dateToTimestamp(LocalDate.ofEpochDay(0))));
            if (DAYS.between(lastUpdated, LocalDate.now()) != 0) {
                //if yesterday indicated that the streak should be increased
                if (sharedPreferences.getBoolean(getString(R.string.increase_streak), false) == true) {
                    streak = streak + 1;
                    if (streak > maxStreak) {
                        streak = maxStreak;
                    }
                    editor.putInt(getString(R.string.streak), streak);
                    editor.putBoolean(getString(R.string.increase_streak), false);
                } else { //if the streak should not have been increased
                    List<Task> yesterdayTasks = taskDatabase.taskDao().getDayTasks(Converters.dateToTimestamp(LocalDate.now().minusDays(1)));
                    //if any of the tasks yesterday were failed AND there were tasks yesterday
                    if (areAllTasksCompletedAtLeastOne(yesterdayTasks) == false && yesterdayTasks.size() > 0) {
                        streak = 0;
                        editor.putInt(getString(R.string.streak), streak);
                        editor.putBoolean(getString(R.string.increase_streak), false);
                    }
                }
                //update the "last updated" date to today
                editor.putString(getString(R.string.last_updated), Converters.dateToTimestamp(LocalDate.now()));
                editor.apply();
            }

            List<Task> todayTasks = taskDatabase.taskDao().getDayTasks(Converters.dateToTimestamp(LocalDate.now()));
            if (areAllTasksCompletedAtLeastOne(todayTasks) == true) {
                maxStreak = maxStreak+1;
                streak = streak+1;
                editor.putBoolean(getString(R.string.increase_streak), true);
            } else {
                editor.putBoolean(getString(R.string.increase_streak), false);
            }
            editor.apply();
            //if the new streak is greater than the maximum theoretical streak, then set it to the max
            System.out.println("max: " + maxStreak);
            if (streak > maxStreak) {
                streak = maxStreak;
            }
            final int finalStreak = streak;
            //--------------------------------
            handler.post(() -> {
                TextView streakCounter = findViewById(R.id.streakCounter);
                streakCounter.setText(String.valueOf(finalStreak));
                ImageView streakImage = findViewById(R.id.streakImage);

                //work out the saturation
                float sat = 0; //if no streak, no saturation
                //otherwise, start from 50%
                if (finalStreak > 0) {
                    sat = (float) (0.5 + (0.5 * (float) finalStreak / 100f));
                }
                //bounding
                if (sat > 1) { sat = 1; }
                //set the color
                int color = ColorUtils.HSLToColor(new float[]{ 0.25f, sat, 0.45f });
                //set both the streakImage and the streakCounter colors
                streakImage.setColorFilter(color);
                streakCounter.setTextColor(color);
            });
        });
    }

    /**
     * Returns whether all of today's tasks were completed AND if there were tasks today
     * @param todayTasks all of today's tasks
     * @return whether there are tasks AND they are all completed
     */
    public boolean areAllTasksCompletedAtLeastOne(List<Task> todayTasks) {
        if (todayTasks.size() > 0) { //if there are tasks today
            for (Task task : todayTasks) {
                if (task.getComplete() == false) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Add a task to the database
     * @param name the name of the task
     */
    public void addTask(String name) {
        //ROOM Threads
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            //Background work here
            Task task = new Task(name);
            long id = taskDatabase.taskDao().insert(task);
            task.setId((int) id);
            handler.post(() -> {
                //UI Thread work here
                // Add a new task
                adapter.addTask(0, task);
                // Notify the adapter that an item was inserted at position 0
                adapter.notifyItemInserted(0);
                //rvTasks.scheduleLayoutAnimation();
                rvTasks.scrollToPosition(0);
                //rvTasks.scheduleLayoutAnimation();
            });
        });
        updateStreak();
    }

    /**
     * Add multiple tasks
     * @param names the names of the tasks
     */
    public void addTasks(ArrayList<String> names) {
        //ROOM Threads
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        ArrayList<Task> tasks = new ArrayList<Task>();
        executor.execute(() -> {
            //Background work here
            for (String name : names) {
                Task task = new Task(name);
                long id = taskDatabase.taskDao().insert(task);
                task.setId((int) id);
                tasks.add(task);
            }
            handler.post(() -> {
                for (Task task : tasks) {
                    // Add a new task
                    adapter.addTask(0, task);
                    // Notify the adapter that an item was inserted at position 0
                    adapter.notifyItemInserted(0);
                    //rvTasks.scheduleLayoutAnimation();
                    rvTasks.scrollToPosition(0);
                    //rvTasks.scheduleLayoutAnimation();
                }
            });
        });
        updateStreak();

    }

    /** Called when the user taps the FAB button */
    public void fabButton(View view) {

        // Use the Builder class for convenient dialog construction
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this, R.style.MaterialAlertDialog_Rounded);
        LayoutInflater inflater = LayoutInflater.from(this);
        View editTextView = inflater.inflate(R.layout.text_input, null);
        EditText input = editTextView.findViewById(R.id.editName);
        input.requestFocus();

        builder.setMessage("Name")
                .setView(editTextView)
                .setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        //get the name of the Task to add
                        String name = input.getText().toString();
                        //add the task
                        addTask(name);
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        // User cancelled the dialog
                    }
                });
        AlertDialog dialog = builder.create();
        dialog.getWindow().setDimAmount(0.0f);
        dialog.show();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);

    }


}