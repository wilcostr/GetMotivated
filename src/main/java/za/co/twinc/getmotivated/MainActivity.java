package za.co.twinc.getmotivated;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.SpannedString;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.ivbaranov.mfb.MaterialFavoriteButton;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.InterstitialAd;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;


public class MainActivity extends AppCompatActivity {

    private final String MAIN_PREFS = "main_app_prefs";
    private AdView adView;

    private final int DISPLAY_NUM = 5;

    private String[] values;
    private Set<String> favs;

    private boolean viewingFavs;

    private MySimpleArrayAdapter adapter;
    private ListView listview;
    private SwipeRefreshLayout refreshLayout;

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

        // Initialise interstitial ad
        mInterstitialAd = new InterstitialAd(this);
        mInterstitialAd.setAdUnitId(getString(R.string.ad_unit_interstitial));
        mInterstitialAd.loadAd(new AdRequest.Builder()
                .addTestDevice("5F2995EE0A8305DEB4C48C77461A7362")
                .build());

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
        // Load previous set
        if (mainLog.getInt("refreshCount", 0) != 0) {
            for (int i = 1; i <= DISPLAY_NUM; i++)
                values[i - 1] = mainLog.getString("oldQuote" + i, "quote not loaded");
        }
        viewingFavs = false;

        // Initialise list of quotes
        listview = findViewById(R.id.listView);
        adapter = new MySimpleArrayAdapter(this, values);
        listview.setAdapter(adapter);
    }

    @Override
    public void onBackPressed(){
        if (viewingFavs){
            ActionBar actionBar = getSupportActionBar();
            if (actionBar!=null)
                actionBar.setTitle(R.string.app_name);

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
    protected void onResume(){
        // Load next set of motivation
        SharedPreferences mainLog = getSharedPreferences(MAIN_PREFS, 0);
        if (mainLog.getInt("loadCount", 0) < DISPLAY_NUM)
            loadNewMotivation();
        super.onResume();
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.option_menu, menu); //your file name
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
            case R.id.menu_why_ads:
                whyAds();
                return true;
            case R.id.action_refresh:
                refreshQuotes();
                return true;
            case R.id.action_favorites:
                showFavorites();
                return true;
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

        // Give 5 ad-free refreshes
        if (mainLog.getInt("refreshCount",0)<4)
            return;

        if (mInterstitialAd.isLoaded()) {
            mInterstitialAd.show();
        } else {
            mInterstitialAd.loadAd(new AdRequest.Builder().build());
        }
    }

    private void refreshQuotes(){
        if (viewingFavs) {
            refreshLayout.setRefreshing(false);
            return;
        }
        loadOrShowAd();

        // Increment the stored counter
        SharedPreferences mainLog = getSharedPreferences(MAIN_PREFS, 0);
        SharedPreferences.Editor editor = mainLog.edit();
        editor.putInt("refreshCount", mainLog.getInt("refreshCount", 0)+1);
        editor.apply();

        listview.smoothScrollToPosition(0);
        refreshLayout.setRefreshing(true);
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                displayQuotes();
            }
        },2500);
    }

    private void displayQuotes(){
        SharedPreferences mainLog = getSharedPreferences(MAIN_PREFS, 0);
        SharedPreferences.Editor editor = mainLog.edit();

        // Return and retry after 5s if all quotes not loaded
        if (mainLog.getInt("loadCount", 0) < DISPLAY_NUM){
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    displayQuotes();
                }
            },5000);
            return;
        }

        for (int i = 1; i <= DISPLAY_NUM; i++) {
            values[i - 1] = mainLog.getString("quote" + i, "quote not loaded");
            editor.putString("oldQuote"+i, values[i-1]);
        }
        adapter.notifyDataSetChanged();
        refreshLayout.setRefreshing(false);
        editor.putInt("loadCount", 0);
        editor.apply();
        loadNewMotivation();
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

            final TextView textView = convertView.findViewById(R.id.textView_motivation);
            SpannableStringBuilder str = new SpannableStringBuilder(values[position]);
            int separatorIdx = values[position].lastIndexOf('-');
            if (separatorIdx > -1)
                str.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        separatorIdx, str.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            textView.setText(str);

            // Share button
            ImageButton shareButton = convertView.findViewById(R.id.button_share_motivation);
            shareButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Intent sharingIntent = new Intent(android.content.Intent.ACTION_SEND);
                    sharingIntent.setType("text/plain");
                    SpannedString str = (SpannedString) textView.getText();
                    String shareText = str.toString();
                    sharingIntent.putExtra(android.content.Intent.EXTRA_TEXT, shareText);

                    if (Build.VERSION.SDK_INT >= 22) {
                        // Share through a BroadcastReceiver to detect if sharing to Facebook
                        Intent receiverIntent = new Intent(getApplicationContext(), MotivationReceiver.class);
                        receiverIntent.putExtra("Motivation",shareText);
                        PendingIntent pendingIntent = PendingIntent.getBroadcast(getApplicationContext(),
                                0, receiverIntent, PendingIntent.FLAG_UPDATE_CURRENT);

                        startActivity(Intent.createChooser(sharingIntent,
                                getResources().getText(R.string.motivation_share),
                                pendingIntent.getIntentSender()));
                    }
                    else{
                        // We cannot detect if Facebook was selected, so copy motivation to clipboard
                        // TODO: Remove code duplication of below functionality
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("Motivation", shareText);
                        if (clipboard != null)
                            clipboard.setPrimaryClip(clip);
                        startActivity(Intent.createChooser(sharingIntent,
                                getResources().getString(R.string.motivation_share)));
                        Toast.makeText(getApplicationContext(), getResources().getString(R.string.motivation_copied), Toast.LENGTH_LONG).show();
                    }
                }
            });

            // Favorite button
            MaterialFavoriteButton favoriteButton = convertView.findViewById(R.id.button_favorite_motivation);

            // Flip button according to status of current quote
            favoriteButton.setFavorite(favs.contains(textView.getText().toString()));

            favoriteButton.setOnFavoriteChangeListener(new MaterialFavoriteButton.OnFavoriteChangeListener() {
                @Override
                public void onFavoriteChanged(MaterialFavoriteButton buttonView, boolean favorite) {
                    if (favorite)
                        favs.add(textView.getText().toString());
                    else
                        favs.remove(textView.getText().toString());

                    SharedPreferences mainLog = getSharedPreferences(MAIN_PREFS, 0);
                    SharedPreferences.Editor editor = mainLog.edit();
                    editor.putStringSet("favorites", favs);
                    editor.apply();
                }
            });

            return convertView;
        }

        public int getCount(){ return values.length; }
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

    // BroadcastReceiver to detect if sharing to Facebook
    private class MotivationReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // We can only check for Facebook on SDK 22 and above
            if (Build.VERSION.SDK_INT >= 22) {
                ComponentName target = intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT);
                if (target != null) {
                    if (target.getClassName().contains("facebook")) {
                        //We cannot send text to Facebook, so copy motivation to clipboard
                        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("Motivation", intent.getStringExtra("Motivation"));
                        if (clipboard != null)
                            clipboard.setPrimaryClip(clip);
                        Toast.makeText(context, context.getResources().getString(R.string.motivation_copied),
                                Toast.LENGTH_LONG).show();
                    }
                }
            }
        }
    }

}