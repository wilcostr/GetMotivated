package za.co.twinc.getmotivated;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.text.TextPaint;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Toast;

import com.github.ivbaranov.mfb.MaterialFavoriteButton;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.InterstitialAd;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static com.google.android.gms.common.util.ArrayUtils.contains;


public class MainActivity extends AppCompatActivity {

    private static final int MY_PERMISSION_REQUEST = 123;
    public static final String PRIMARY_NOTIF_CHANNEL = "default";
    private final String MAIN_PREFS = "main_app_prefs";

    private final int   DISPLAY_NUM = 5;
    private int         IMAGE_NUM = 64;
    private int         IMAGE_STACK_SIZE = 30;

    private String[] values;
    private Set<String> favs;

    private boolean viewingFavs;
    private Bitmap tempBmp;
    private int tempP;
    NotificationManager mNotifyMgr;

    private MySimpleArrayAdapter adapter;
    private ListView listview;
    private SwipeRefreshLayout refreshLayout;
    private Menu optionsMenu;

    private AdView adView;
    private InterstitialAd mInterstitialAd;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        refreshLayout = findViewById(R.id.refreshContainer);
        refreshLayout.setColorSchemeResources(R.color.colorPrimaryDark, R.color.colorAccent);
        refreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                refreshQuotes();
            }
        });

        // Create main share preference log
        SharedPreferences mainLog = getSharedPreferences(MAIN_PREFS, 0);
        SharedPreferences.Editor editor = mainLog.edit();

        // Initialise interstitial ad
        mInterstitialAd = new InterstitialAd(this);
        mInterstitialAd.setAdUnitId(getString(R.string.ad_unit_interstitial));

        mInterstitialAd.loadAd(new AdRequest.Builder()
                .addTestDevice("5F2995EE0A8305DEB4C48C77461A7362")
                .build());

        // Don't show ad in the first 2 minutes of activity
        editor.putLong("ad_time", System.currentTimeMillis());
        editor.apply();

        // Scroll to top quote after ad is closed
        mInterstitialAd.setAdListener(new AdListener(){
            @Override
            public void onAdClosed(){
                listview.smoothScrollToPosition(0);
                mInterstitialAd.loadAd(new AdRequest.Builder()
                        .addTestDevice("5F2995EE0A8305DEB4C48C77461A7362")
                        .build());
            }
        });

        // No banner ad if premium
        adView = findViewById(R.id.adView);
        if (!mainLog.getBoolean("premium", false)){
            // Load add
            AdRequest adRequest = new AdRequest.Builder()
                    .addTestDevice("5F2995EE0A8305DEB4C48C77461A7362")
                    .build();
            adView.loadAd(adRequest);
            adView.setAdListener(new AdListener(){
                @Override
                public void onAdLoaded(){
                    adView.setVisibility(View.VISIBLE);
                }
            });
        }


        //TODO: Image refresh only after text refresh (if possible)

        // Create notification channel. No problem if already created previously
        mNotifyMgr = (NotificationManager)getApplicationContext().getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    PRIMARY_NOTIF_CHANNEL, PRIMARY_NOTIF_CHANNEL, NotificationManager.IMPORTANCE_LOW);
            mNotifyMgr.createNotificationChannel(channel);
        }

        viewingFavs = false;

        // Load favorite quotes
        favs = new LinkedHashSet<>();
        Set<String> prefSet = mainLog.getStringSet("favorites", null);
        if (prefSet != null)
            favs.addAll(prefSet);

        // Load next set of motivation
        if (mainLog.getInt("loadCount", 0) < DISPLAY_NUM)
            loadNewMotivation();

        // Load base quotes
        values = getResources().getStringArray(R.array.starting_quotes);

        if (mainLog.getInt("refreshCount", 0) != 0) {
            // Load previous set
            for (int i = 1; i <= DISPLAY_NUM; i++)
                values[i - 1] = mainLog.getString("oldQuote" + i, "quote not loaded");
        }
        else {
            // Load some images only if this is still the first set of quotes
            // This might change the image for fixed quotes, but will only happy once
            // and might entice repeated use...
            loadNewImages();
        }

        if (favs.size() != 0){
            for (String object : favs){
                if (mainLog.getInt(object.substring(4,14), 0) == 0){
                    Random r = new Random();
                    editor.putInt(object.substring(4,14), r.nextInt(IMAGE_NUM+1));
                    editor.apply();
                }
            }
        }

        // Initialise list of quotes
        listview = findViewById(R.id.listView);
        adapter = new MySimpleArrayAdapter(this, values);
        listview.setAdapter(adapter);
    }

    @Override
    public void onBackPressed(){
        if (viewingFavs){
            ActionBar actionBar = getSupportActionBar();
            if (actionBar!=null) {
                actionBar.setTitle(R.string.app_name);
                actionBar.setDisplayHomeAsUpEnabled(false);
            }

            optionsMenu.findItem(R.id.action_favorites).setVisible(true);
            optionsMenu.findItem(R.id.action_refresh).setVisible(true);

            // Load base quotes
            SharedPreferences mainLog = getSharedPreferences(MAIN_PREFS, 0);
            values = getResources().getStringArray(R.array.starting_quotes);
            // Load previous set
            if (mainLog.getInt("refreshCount", 0) != 0) {
                for (int i = 1; i <= DISPLAY_NUM; i++)
                    values[i - 1] = mainLog.getString("oldQuote" + i, "quote not loaded");
            }
            adapter = new MySimpleArrayAdapter(this, values);
            listview.setAdapter(adapter);
            viewingFavs = false;
        }
        else
            super.onBackPressed();
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.option_menu, menu); //your file name
        optionsMenu = menu;
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_share:
                String uri = "http://play.google.com/store/apps/details?id=" + getPackageName();
                Intent sharingIntent = new Intent(android.content.Intent.ACTION_SEND);
                sharingIntent.setType("text/plain");
                sharingIntent.putExtra(Intent.EXTRA_SUBJECT,getString(R.string.app_name));
                sharingIntent.putExtra(Intent.EXTRA_TEXT, uri);
                startActivity(Intent.createChooser(sharingIntent, getResources().getText(R.string.share)));
                return true;
            case R.id.menu_feedback:
                feedback();
                return true;
            case R.id.menu_rate:
                rateApp();
                return true;
            case R.id.menu_why_ads:
                whyAds();
                return true;
            case R.id.action_refresh:
                refreshQuotes();
                return true;
            case R.id.menu_privacy_policy:
                Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://sites.google.com/view/twincapps-privacypolicy/home"));
                startActivity(browserIntent);
                return true;
            case R.id.action_favorites:
                ActionBar actionBar = getSupportActionBar();
                if (actionBar != null)
                    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                item.setVisible(false);
                optionsMenu.findItem(R.id.action_refresh).setVisible(false);
                showFavorites();
                return true;
            case android.R.id.home:
                onBackPressed();

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void showFavorites(){
        ActionBar actionBar = getSupportActionBar();
        if (actionBar!=null)
            actionBar.setTitle(R.string.favorites);

        values = new String[favs.size()];
        int i = 0;
        for (String object : favs){
            values[i] = object;
            i++;
        }
        adapter = new MySimpleArrayAdapter(this, values);
        listview.setAdapter(adapter);
        viewingFavs = true;
    }

    private void loadOrShowAd(){
        SharedPreferences mainLog = getSharedPreferences(MAIN_PREFS, 0);

        // Return if premium
        if (mainLog.getBoolean("premium", false))
            return;

        // Give 3 ad-free screens (2 refreshes)
        if (mainLog.getInt("refreshCount",0)<2)
            return;

        // Return if two minutes since previous ad / start of session has not passed
        if (System.currentTimeMillis() - mainLog.getLong("ad_time", 0L) < 2*60*1000)
            return;

        if (mInterstitialAd.isLoaded()) {
            mInterstitialAd.show();
            SharedPreferences.Editor editor = mainLog.edit();
            editor.putLong("ad_time", System.currentTimeMillis());
            editor.apply();
        } else {
            mInterstitialAd.loadAd(new AdRequest.Builder()
                    .addTestDevice("5F2995EE0A8305DEB4C48C77461A7362")
                    .build());
        }
    }

    private void refreshQuotes(){
        if (viewingFavs) {
            refreshLayout.setRefreshing(false);
            return;
        }
        listview.smoothScrollToPosition(0);
        loadOrShowAd();

        // Increment the stored counter
        SharedPreferences mainLog = getSharedPreferences(MAIN_PREFS, 0);
        SharedPreferences.Editor editor = mainLog.edit();
        editor.putInt("refreshCount", mainLog.getInt("refreshCount", 0)+1);
        editor.apply();

        // Select images for the new quotes
        loadNewImages();

        refreshLayout.setRefreshing(true);
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                displayNewQuotes();
            }
        },2500);
    }

    private void displayNewQuotes(){
        SharedPreferences mainLog = getSharedPreferences(MAIN_PREFS, 0);
        SharedPreferences.Editor editor = mainLog.edit();

        // Return and retry after 5s if all quotes not loaded
        if (mainLog.getInt("loadCount", 0) < DISPLAY_NUM){
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    displayNewQuotes();
                }
            },5000);
            return;
        }

        for (int i = 1; i <= DISPLAY_NUM; i++) {
            values[i - 1] = mainLog.getString("quote" + i, "quote not loaded");
            // Save the currently visible quotes as oldQuote[1..5]
            editor.putString("oldQuote"+i, values[i-1]);
        }
        adapter.notifyDataSetChanged();
        refreshLayout.setRefreshing(false);
        editor.putInt("loadCount", 0);

        editor.apply();
        loadNewMotivation();
    }


    private void loadNewImages(){
        // Create a stack of images that are not allowed to repeat
        SharedPreferences mainLog = getSharedPreferences(MAIN_PREFS, 0);
        int stack [] = new int[IMAGE_STACK_SIZE];
        for (int i = 0; i < IMAGE_STACK_SIZE; i++){
            stack[i] = mainLog.getInt("image"+i, 0);
        }

        // Select a random background image
        Random r = new Random();
        int refreshCount = mainLog.getInt("refreshCount",0);
        SharedPreferences.Editor editor = mainLog.edit();
        for (int i = 0; i < DISPLAY_NUM; i++){
            int newImage = r.nextInt(IMAGE_NUM+1);
            while (contains(stack, newImage)){
                newImage = r.nextInt(IMAGE_NUM+1);
            }
            int imagePosition = (DISPLAY_NUM*refreshCount + i)%IMAGE_STACK_SIZE;
            stack[imagePosition] = newImage;
            editor.putInt("image"+imagePosition, newImage);
        }
        editor.apply();
    }

    private void whyAds(){
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setTitle(R.string.why_ads);
        builder.setMessage(R.string.ads_msg);

        final EditText input = new EditText(getApplicationContext());
        input.setTextColor(getResources().getColor(android.R.color.black));
        input.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        builder.setView(input);

        builder.setPositiveButton(R.string.why_ads, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                if (input.getText().toString().toLowerCase().trim().equals("twincapps")) {
                    // Activate premium
                    SharedPreferences mainPrefs = getSharedPreferences(MAIN_PREFS, 0);
                    SharedPreferences.Editor editor = mainPrefs.edit();
                    editor.putBoolean("premium",true);
                    editor.apply();
                    adView.setVisibility(View.GONE);
                    Toast.makeText(getApplicationContext(), R.string.welcome_premium, Toast.LENGTH_LONG).show();
                }
                else
                    Toast.makeText(getApplicationContext(), R.string.wrong_code, Toast.LENGTH_LONG).show();

            }
        });

        builder.setNeutralButton(R.string.btn_contact_us, new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {
                Intent emailIntent = new Intent(Intent.ACTION_SENDTO);
                emailIntent.setData(Uri.parse("mailto:dev.twinc@gmail.com?subject=Get%20Motivated%20premium"));

                try {
                    startActivity(emailIntent);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this,getResources().getString(R.string.txt_no_email),
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {

            }
        });
        builder.create().show();
    }

    private void feedback(){
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.feedback_title));
        builder.setMessage(getString(R.string.feedback_msg));

        builder.setPositiveButton(getResources().getString(R.string.btn_rate_app), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                rateApp();
            }
        });

        builder.setNeutralButton(getResources().getString(R.string.btn_contact_us), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                Intent emailIntent = new Intent(Intent.ACTION_SENDTO);
                emailIntent.setData(Uri.parse("mailto:dev.twinc@gmail.com?subject=GetMotivated%20feedback"));

                try {
                    startActivity(emailIntent);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this,getResources().getString(R.string.txt_no_email),
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        builder.setNegativeButton(getString(android.R.string.cancel), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {

            }
        });

        builder.create().show();
    }

    @SuppressWarnings("deprecation")
    private void rateApp(){
        Uri uri = Uri.parse("market://details?id=" + getPackageName());
        Intent goToMarket = new Intent(Intent.ACTION_VIEW, uri);
        // To count with Play market backstack, After pressing back button,
        // to taken back to our application, we need to add following flags to intent.
        goToMarket.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            goToMarket.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT |
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        else {
            //Suppress deprecation
            goToMarket.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        }

        try {
            startActivity(goToMarket);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("http://play.google.com/store/apps/details?id=" + getPackageName())));
        }
    }


    private class MySimpleArrayAdapter extends ArrayAdapter<String> {
        private final String[] values;

        MySimpleArrayAdapter(Context context, String[] values) {
            super(context, -1, values);
            this.values = values;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup container) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.motivation_card, container, false);
            }

            // Build the formatted String
            SpannableStringBuilder str = new SpannableStringBuilder(values[position]);
            int separatorIdx = values[position].lastIndexOf('-');
            if (separatorIdx > -1)
                str.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        separatorIdx, str.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            final String motivationText = str.toString();


            // Get a handle to the image
            ImageView imageView = convertView.findViewById(R.id.motivation_image);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inMutable = true;

            // Get the background image
            final int displayImage;
            SharedPreferences mainLog = getSharedPreferences(MAIN_PREFS, 0);
            if (viewingFavs){
                displayImage = mainLog.getInt(motivationText.substring(4,14), 2);
            }
            else {
                int refreshCount = mainLog.getInt("refreshCount", 0);
                displayImage = mainLog.getInt("image" + (DISPLAY_NUM * refreshCount + position) % IMAGE_STACK_SIZE, 1);
            }

            final Bitmap bitmap;
            Bitmap bitmap1;
            try {
                @SuppressLint("DefaultLocale") Field drawableField =
                        R.drawable.class.getField(String.format("bg%03d", displayImage));
                bitmap1 = BitmapFactory.decodeResource(getResources(),
                        drawableField.getInt(R.drawable.class), options);
            }
            catch (Exception e){
                bitmap1 = BitmapFactory.decodeResource(getResources(), R.drawable.bg001, options);
            }
            bitmap = bitmap1;
            final Canvas canvas = new Canvas(bitmap);

            // Create text layout
            TextPaint textPaint = new TextPaint();
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(55);
            int textPadding = 12;
            StaticLayout staticLayout = new StaticLayout(str, textPaint, canvas.getWidth()-2*textPadding,
                    Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);

            // Extract text relative position parameters. Lift text slightly above centre
            int textHeight = staticLayout.getHeight() + 2*textPadding;
            int textTop = ((canvas.getHeight() - textHeight)/2) - 3*textPadding;

            // Draw transparent block behind text
            Paint blockPaint = new Paint();
            blockPaint.setColor(Color.BLACK);
            blockPaint.setAlpha(92);
            canvas.drawRect(0, textTop, canvas.getWidth(), textTop+textHeight, blockPaint);

            // Draw the text layout
            canvas.save();
            canvas.translate(textPadding, textTop+textPadding);
            staticLayout.draw(canvas);
            canvas.restore();

            // Set imageView
            imageView.setImageBitmap(bitmap);

            // Share button
            ImageButton shareButton = convertView.findViewById(R.id.button_share_motivation);
            shareButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    shareImage(canvas, bitmap);
                }
            });

            // Favorite button
            MaterialFavoriteButton favoriteButton = convertView.findViewById(R.id.button_favorite_motivation);
            // onFavoriteClick
            favoriteButton.setOnFavoriteChangeListener(new MaterialFavoriteButton.OnFavoriteChangeListener() {
                @Override
                public void onFavoriteChanged(MaterialFavoriteButton buttonView, boolean favorite) {
                    SharedPreferences mainLog = getSharedPreferences(MAIN_PREFS, 0);
                    SharedPreferences.Editor editor = mainLog.edit();
                    if (favorite) {
                        favs.add(motivationText);
                        editor.putInt(motivationText.substring(4,14), displayImage);
                    }
                    else {
                        favs.remove(motivationText);
                        editor.remove(motivationText.substring(4,14));
                    }
                    editor.putStringSet("favorites", favs);
                    editor.apply();
                }
            });
            // Flip button according to status of current quote
            favoriteButton.setFavorite(favs.contains(motivationText));

            // Download button
            ImageButton downloadButton = convertView.findViewById(R.id.button_download);
            downloadButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    downloadImage(canvas, bitmap);
                }
            });

            return convertView;
        }

        public int getCount(){ return values.length; }
    }

    private void downloadImage(Canvas canvas, Bitmap bitmap){
        processImage(canvas, bitmap, 1);
    }

    private void shareImage(Canvas canvas, Bitmap bitmap) {
        processImage(canvas, bitmap, 2);
    }

    // Process the active image with
    // p == 1   download and notify
    // p == 2   download and share
    private void processImage(Canvas canvas, Bitmap bitmap, int p){
        Bitmap savedBitmap = bitmap.copy(bitmap.getConfig(), true);
        addTagToImage(canvas);
        if (Build.VERSION.SDK_INT >= 23){
            // Must first confirm write permission
            checkWritePermissionAndSaveImage(bitmap, p);
        }
        else {
            saveImage(bitmap, p);
        }
        canvas.drawBitmap(savedBitmap,0,0,null);

    }


    private void addTagToImage(Canvas canvas){
        // Draw Google Play + app tag
        Bitmap gm_tag = BitmapFactory.decodeResource(getResources(), R.drawable.gm_tag);
        // 50% transparant block
        Paint blockPaint = new Paint();
        blockPaint.setColor(Color.BLACK);
        blockPaint.setAlpha(92);
        blockPaint.setAlpha(128);
        if (Build.VERSION.SDK_INT >= 21) {
            canvas.drawRoundRect(canvas.getWidth() - gm_tag.getWidth(),
                    canvas.getHeight() - gm_tag.getHeight(),
                    canvas.getWidth()+6,
                    canvas.getHeight()+6,
                    6,
                    6,
                    blockPaint);
        }
        else {
            canvas.drawRect(canvas.getWidth() - gm_tag.getWidth(),
                    canvas.getHeight() - gm_tag.getHeight(),
                    canvas.getWidth(),
                    canvas.getHeight(),
                    blockPaint);
        }

        canvas.drawBitmap(gm_tag,
                canvas.getWidth() - gm_tag.getWidth(),
                canvas.getHeight() - gm_tag.getHeight(),
                null
        );
    }

    private void checkWritePermissionAndSaveImage(Bitmap bitmap, int p){
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {

            // Permission is not granted
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    MY_PERMISSION_REQUEST);

            // Keep the bitmap pointer for saving after permission has been granted
            tempBmp = bitmap;
            tempP = p;

        } else {
            // Permission has already been granted
            saveImage(bitmap, p);
        }
    }

    // What to do after saving the image
    // p == 1   download and notify
    // p == 2   download and share
    private void saveImage(Bitmap bitmap, final int p){
        // Return if external storage not available
        String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            Toast.makeText(getApplicationContext(), R.string.no_storage,
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Get the directory for the user's public pictures directory.
        File storageDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "GetMotivated");
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }

        try {
            File imageFile = File.createTempFile("GetMotivated",".jpg", storageDir);
            OutputStream os = new FileOutputStream(imageFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, os);
            os.close();

            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            Uri contentUri = Uri.fromFile(imageFile);
            mediaScanIntent.setData(contentUri);
            sendBroadcast(mediaScanIntent);

            MediaScannerConnection.scanFile(this, new String[]{imageFile.getAbsolutePath()},
                    new String[]{"image/jpeg"}, new MediaScannerConnection.OnScanCompletedListener() {
                        @Override
                        public void onScanCompleted(String s, Uri uri) {

                            if (p==1) {
                                // Give a notification here
                                // View image intent
                                Intent imageIntent = new Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(uri, "image/*")
                                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

                                // Give notification to open saved image
                                NotificationCompat.Builder notiBuilder = new NotificationCompat.Builder(getApplicationContext(), PRIMARY_NOTIF_CHANNEL)
                                        .setContentTitle(getApplicationContext().getString(R.string.app_name))
                                        .setContentText(getApplicationContext().getString(R.string.image_open))
                                        .setSmallIcon(R.mipmap.gm_icon)
                                        .setLargeIcon(BitmapFactory.decodeResource(getApplicationContext().getResources(), R.mipmap.gm_icon))
                                        .setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, imageIntent, 0))
                                        .setAutoCancel(true)
                                        .setVibrate(new long[]{1000, 200})
                                        .setPriority(NotificationCompat.PRIORITY_LOW);

                                //        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
                                //            notiBuilder.setSmallIcon(R.drawable.nine_png);

                                // Issue notification
                                if (mNotifyMgr != null)
                                    mNotifyMgr.notify(0, notiBuilder.build());
                            }

                            else if (p==2){
                                Intent shareIntent = new Intent(Intent.ACTION_SEND)
                                        .putExtra(Intent.EXTRA_STREAM, uri);
                                shareIntent.setType("image/*");
                                startActivity(Intent.createChooser(shareIntent,
                                        getResources().getText(R.string.motivation_share)));
                            }
                        }
                    });

            if (p==1)
                Toast.makeText(getApplicationContext(), R.string.image_saved, Toast.LENGTH_LONG).show();

        } catch (IOException e) {
            // Unable to create file, likely because external storage is
            // not currently mounted.
            e.printStackTrace();
            Toast.makeText(getApplicationContext(), R.string.download_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String permissions[], @NonNull int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSION_REQUEST: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (tempBmp != null)
                        saveImage(tempBmp, tempP);
                } else {
                    Toast.makeText(getApplicationContext(), R.string.permission_required, Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void loadNewMotivation(){
        //Start network thread here
        String quoteURL = "http://api.forismatic.com/api/1.0/?method=getQuote&lang=en&format=text";
        if ( Locale.getDefault().getLanguage().equals("ru"))
            quoteURL = "http://api.forismatic.com/api/1.0/?method=getQuote&lang=ru&format=text";
        new GetMotivation().execute(quoteURL);
    }

    @SuppressLint("StaticFieldLeak")
    private class GetMotivation extends AsyncTask<String , Void ,String> {
        String server_response;

        @Override
        protected String doInBackground(String... strings) {

            URL url;
            HttpURLConnection urlConnection;

            try {
                url = new URL(strings[0]);
                urlConnection = (HttpURLConnection) url.openConnection();

                int responseCode = urlConnection.getResponseCode();

                if(responseCode == HttpURLConnection.HTTP_OK){
                    server_response = readStream(urlConnection.getInputStream());
                    return server_response;
                }

            } catch (IOException e) {
                e.printStackTrace();
            }

            return null;
        }

        @Override
        protected void onPostExecute(String s) {
            super.onPostExecute(s);
            if (s != null) {
                // Format quote
                s = s.replace("(","\n- ").replace(")","");

                // Save quote to mainLog
                SharedPreferences mainLog = getSharedPreferences(MAIN_PREFS, 0);
                SharedPreferences.Editor editor = mainLog.edit();

                int nextQuote = mainLog.getInt("loadCount", 0) + 1;

                // Verify that we indeed have a new quote
                if (s.equals(mainLog.getString("quote"+(nextQuote-1), "no quote")))
                    nextQuote -= 1;

                editor.putString("quote"+nextQuote, s);
                editor.putInt("loadCount", nextQuote);
                editor.apply();

                if (nextQuote != DISPLAY_NUM)
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            loadNewMotivation();
                        }
                    },1500);
            }
        }
    }

    // Converting InputStream to String
    private String readStream(InputStream in) {

        BufferedReader reader = null;
        StringBuilder response = new StringBuilder();
        try {
            reader = new BufferedReader(new InputStreamReader(in));
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return response.toString();
    }
}