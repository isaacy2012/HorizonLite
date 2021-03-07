package com.innerCat.sunset.activities;

import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.room.ColumnInfo;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.innerCat.sunset.R;
import com.innerCat.sunset.Task;
import com.innerCat.sunset.factories.AnimationListenerFactory;
import com.innerCat.sunset.factories.TaskDatabaseFactory;
import com.innerCat.sunset.factories.TextWatcherFactory;
import com.innerCat.sunset.recyclerViews.TasksAdapter;
import com.innerCat.sunset.room.DBMethods;
import com.innerCat.sunset.room.TaskDatabase;
import com.innerCat.sunset.widgets.HomeWidgetProvider;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.time.temporal.ChronoUnit.DAYS;


public class MainActivity extends AppCompatActivity {

    int ANIMATION_DURATION = 0;

    //private fields for the Dao and the Database
    public static TaskDatabase taskDatabase;
    RecyclerView rvTasks;
    TasksAdapter adapter;
    SharedPreferences sharedPreferences;
    int defaultColor;

    @ColumnInfo(defaultValue = "0")

    private final int LIST_TASK_REQUEST = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //constants
        ANIMATION_DURATION = getResources().getInteger(R.integer.animation_duration);

        //streak
        sharedPreferences = getSharedPreferences("preferences", Context.MODE_PRIVATE);

        //if the user hasn't seen the update dialog yet, then show it
        if (sharedPreferences.getBoolean("update_1_dot_1", false) == false) {
            showUpdateDialog();
        }

        //get the default text color
        TextView messageTextView = findViewById(R.id.messageTextView);
        defaultColor = messageTextView.getCurrentTextColor();

        //initialise the database
        taskDatabase = TaskDatabaseFactory.getTaskDatabase(this);

        //get the recyclerview in activity layout
        rvTasks = findViewById(R.id.rvTasks);

        //get the swipeRefreshLayout
        final SwipeRefreshLayout swipeRefreshLayout= findViewById(R.id.swipeRefreshLayout);
        swipeRefreshLayout.setOnRefreshListener(() -> {
            adapter.removeAllChecked();
            adapter.notifyDataSetChanged();
            swipeRefreshLayout.setRefreshing(false);
        });

        //ROOM Threads
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            //Background work here
            //NB: This is the new thread in which the database stuff happens
            List<Task> tasks = taskDatabase.taskDao().getAllUncompletedTasks();
            for (int i = 0; i < tasks.size(); i++) {
                if (DAYS.between(tasks.get(i).getDate(), LocalDate.now()) != 0) {
                    Task task = tasks.get(i);
                    //noinspection SuspiciousListRemoveInLoop since we are adding it back at index 0
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
    }

    /**
     * Set a particular update as seen
     * @param updateString the update string
     */
    private void setUpdateSeen(String updateString) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(updateString, true);
        editor.apply();
    }

    /**
     * Show the update dialog, which shows users what the new features in an update are
     */
    private void showUpdateDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this, R.style.MaterialAlertDialog_Rounded);
        LayoutInflater inflater = LayoutInflater.from(this);
        View updateText = inflater.inflate(R.layout.update_text, null);
        TextView updateTextView = updateText.findViewById(R.id.updateTextView);

        String updateBodyString = getString(R.string.update_1_dot_1);
        updateTextView.setText(Html.fromHtml(updateBodyString, Html.FROM_HTML_MODE_COMPACT));
        updateTextView.setMovementMethod(new LinkMovementMethod());

        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int height = displayMetrics.heightPixels;
        updateTextView.setMaxHeight(height/2);

        String updateString = ("What's new in version 1.1:");

        builder.setMessage(updateString)
                .setView(updateText)
                .setPositiveButton("Ok", ( dialog, id ) -> {
                    setUpdateSeen("update_1_dot_1");
                })
                .setNeutralButton("Leave a review", null);
        AlertDialog dialog = builder.create();
        dialog.getWindow().setDimAmount(0.0f);
        dialog.show();
        dialog.setOnCancelListener(dialog1 -> {
            setUpdateSeen("update_1_dot_1");
        });
        Button neutralButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        neutralButton.setOnClickListener(v -> {
            // dialog doesn't dismiss
            setUpdateSeen("update_1_dot_1");
            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_VIEW);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setData(Uri.parse(getString(R.string.google_play_store_listing)));
            startActivity(intent);
        });
    }


    /**
     * When the view is resumed
     */
    public void onResume() {
        super.onResume();
        updateStreak();
    }


    /**
     * Add tasks to the adapter given ids of Tasks *already added* into the database
     * @param ids the ids of the Tasks
     */
    private void addIds(ArrayList<Integer> ids) {
        //ROOM Threads
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            //Background work here
            ArrayList<Task> newTasks = new ArrayList<>();
            for (Integer id : ids) {
                Task addTask = taskDatabase.taskDao().getTask(id);
                if (addTask != null) {
                    newTasks.add(addTask);
                }
            }
            handler.post(() -> {
                for (Task addTask : newTasks) {
                    adapter.addTask(0, addTask);
                    adapter.notifyItemInserted(0);
                }
            });
        });
    }


    /**
     * Set today to complete at least one thing
     */
    public void todayComplete() {
        final SharedPreferences.Editor editor = sharedPreferences.edit();
        if (sharedPreferences.getBoolean(getString(R.string.today_at_least_one_completed), false) == false) {
            editor.putBoolean(getString(R.string.today_at_least_one_completed), true);
            editor.apply();
        }
    }

    /**
     * Update the messageTextView
     */
    private void updateMessage() {
        //ROOM Threads
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            //Background work here
            List<Task> tasks = taskDatabase.taskDao().getAllUncompletedTasks();
            final int numTasks = tasks.size();

            handler.post(() -> {
                int textAnimationDuration = getResources().getInteger(R.integer.text_animation_duration);
                TextView messageTextView = findViewById(R.id.messageTextView);
                int colorFrom = defaultColor;
                String messageText;
                if (numTasks == 0) {
                    if (sharedPreferences.getBoolean(getString(R.string.today_at_least_one_completed), false) == true) {
                        messageText = getString(R.string.congratulation_complete);
                    } else {
                        messageText = "You have no tasks today";
                    }
                } else {
                    messageText = "You have " + numTasks + " " + (numTasks > 1 ? "tasks" : "task") + " remaining today" ;
                }
                if (messageText.equals(messageTextView.getText()) == false) { //if the message is changing, then do the animation
                    messageTextView.setTextColor(Color.TRANSPARENT);
                    ValueAnimator reverseAnimation = ValueAnimator.ofObject(new ArgbEvaluator(), Color.TRANSPARENT, colorFrom);
                    reverseAnimation.addUpdateListener(animator -> {
                        messageTextView.setTextColor((Integer) animator.getAnimatedValue());
                    });
                    reverseAnimation.setDuration(textAnimationDuration);
                    if (messageTextView.getText().equals("")) { //if this is the first time doing the text, don't need to fade out
                        messageTextView.setText(messageText);
                        reverseAnimation.start();
                    } else { //otherwise, fade out and fade back in
                        AnimatorSet as = new AnimatorSet();
                        ValueAnimator animationToTransparent = ValueAnimator.ofObject(new ArgbEvaluator(), colorFrom, Color.TRANSPARENT);
                        animationToTransparent.addUpdateListener(animator -> {
                            messageTextView.setTextColor((Integer) animator.getAnimatedValue());
                        });
                        animationToTransparent.setDuration(textAnimationDuration);
                        as.play(animationToTransparent).before(reverseAnimation);
                        as.start();

                        //delay the change of the messageText until the animation is half-complete and the text is fully faded out
                        final Handler editHandler = new Handler(Looper.getMainLooper());
                        editHandler.postDelayed(() -> {
                            messageTextView.setText(messageText);
                        }, textAnimationDuration);
                    }
                }
            });
        });

    }

    /*
     * Update the streak
     */
    public void updateStreak() {
        updateMessage();
        HomeWidgetProvider.broadcastUpdate(this);

        //ROOM Threads
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            final int finalStreak = DBMethods.updateAndGetStreak(this, taskDatabase);
            //--------------------------------
            handler.post(() -> {
                displayStreak(finalStreak);
                setHighScoreText();
            });
        });
    }


    /**
     * Display the streak
     * @param finalStreak the final streak from updateStreak()
     */
    private void displayStreak(int finalStreak) {
        TextView streakCounter = findViewById(R.id.streakCounter);
        String oldStreakText = streakCounter.getText().toString();
        int oldStreak = -1;
        try {
            oldStreak = Integer.parseInt(oldStreakText);
        } catch (NumberFormatException ignored) {}

        streakCounter.setText(String.valueOf(finalStreak));

        //if the streak has changed from 0 to >0 or >0 to 0
        if (((oldStreak > 0) == (finalStreak > 0)) == false) {
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
            animateStreakToColor(color);
        }
    }

    /**
     * Animates the color of the streakImage and streakCounter from the current color of the streakCounter to the specified color
     * @param colorTo the new color for the streakImage and streakCounter to be
     */
    private void animateStreakToColor( int colorTo) {
        TextView streakCounter = findViewById(R.id.streakCounter);
        ImageButton streakButton = findViewById(R.id.streakButton);
        int colorFrom = streakCounter.getCurrentTextColor();
        ValueAnimator colorAnimation = ValueAnimator.ofObject(new ArgbEvaluator(), colorFrom, colorTo);
        colorAnimation.addUpdateListener(animator -> {
            streakCounter.setTextColor((Integer)animator.getAnimatedValue());
            streakButton.setColorFilter((Integer)animator.getAnimatedValue());
        });
        colorAnimation.setDuration(ANIMATION_DURATION);
        colorAnimation.start();
    }



    /**
     * Update an existing task in the database
     * @param task the task to change
     * @param position its position in the RecyclerView
     */
    public void updateTask(Task task, int position) {
        //ROOM Threads
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            //Background work here
            taskDatabase.taskDao().update(task);
            handler.post(() -> {
                //UI Thread work here
                // Notify the adapter that an item was changed at position
                adapter.notifyItemChanged(position);
            });
        });
    }

    /**
     * Add a task to the database
     * @param name the name of the task
     */
    private void addTask(String name) {
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
                updateStreak();
            });
        });
    }


    /** Called when the user taps the FAB button */
    public void fabButton(View view) {

        // Use the Builder class for convenient dialog construction
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this, R.style.MaterialAlertDialog_Rounded);

        //get the UI elements
        FloatingActionButton fab = findViewById(R.id.floatingActionButton);
        fab.setVisibility(View.INVISIBLE);
        View editTextView = LayoutInflater.from(this).inflate(R.layout.text_input, null);
        EditText input = editTextView.findViewById(R.id.editName);

        //Set the capitalisation from sharedPreferences
        if (sharedPreferences.getBoolean("capitalization", true) == true) {
            input.setInputType(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        } else {
            input.setInputType(InputType.TYPE_CLASS_TEXT);
        }

        input.requestFocus();

        builder.setMessage("Name")
                .setView(editTextView)
                .setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        //get the name of the Task to add
                        String name = input.getText().toString();
                        //add the task
                        addTask(name);
                        fab.setVisibility(View.VISIBLE);
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        // User cancelled the dialog
                        fab.setVisibility(View.VISIBLE);
                    }
                });
        AlertDialog dialog = builder.create();
        dialog.setOnCancelListener(dialog1 -> {
            // dialog dismisses
            fab.setVisibility(View.VISIBLE);
        });
        dialog.getWindow().setDimAmount(0.0f);
        dialog.show();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        Button okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        okButton.setEnabled(false);
        input.addTextChangedListener(TextWatcherFactory.getNonEmptyTextWatcher(input, okButton));
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
     * When the streak button is pressed
     * @param view
     */
    public void onStreakButton( View view ) {
        TextView highScoreTextView = findViewById(R.id.highScoreTextView);
        setHighScoreText();
        if (highScoreTextView.getVisibility() == View.VISIBLE) {
            Animation slideToRight = AnimationUtils.loadAnimation(this, R.anim.slide_to_right);
            highScoreTextView.startAnimation(slideToRight);
            slideToRight.setAnimationListener(AnimationListenerFactory.getAnimationListener(highScoreTextView, View.INVISIBLE));
        } else {
            Animation slideFromRight = AnimationUtils.loadAnimation(this, R.anim.slide_from_right);
            highScoreTextView.startAnimation(slideFromRight);
            slideFromRight.setAnimationListener(AnimationListenerFactory.getAnimationListener(highScoreTextView, View.VISIBLE));
        }
    }

    /**
     * Set the high score text
     */
    private void setHighScoreText() {
        TextView highScoreTextView = findViewById(R.id.highScoreTextView);
        String html = "<b>BEST&nbsp&nbsp</b>" + sharedPreferences.getInt(getString(R.string.highscore), 0);
        highScoreTextView.setText(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT));
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
                ArrayList<Integer> ids = data.getIntegerArrayListExtra("ids");
                addIds(ids);
            }
        }
    }

}