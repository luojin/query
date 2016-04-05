package de.kugihan.dictionaryformids.hmi_android;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.Toast;

import java.util.Locale;
import java.util.Observable;
import java.util.Observer;

import de.kugihan.dictionaryformids.dataaccess.DictionaryDataFile;
import de.kugihan.dictionaryformids.dataaccess.fileaccess.DfMInputStreamAccess;
import de.kugihan.dictionaryformids.dataaccess.fileaccess.FileDfMInputStreamAccess;
import de.kugihan.dictionaryformids.dataaccess.fileaccess.NativeZipInputStreamAccess;
import de.kugihan.dictionaryformids.general.DictionaryException;
import de.kugihan.dictionaryformids.general.Util;
import de.kugihan.dictionaryformids.hmi_android.data.AndroidUtil;
import de.kugihan.dictionaryformids.hmi_android.data.DfMTranslationExecutor;
import de.kugihan.dictionaryformids.hmi_android.data.Dictionary;
import de.kugihan.dictionaryformids.hmi_android.data.DictionaryType;
import de.kugihan.dictionaryformids.hmi_android.data.DictionaryVector;
import de.kugihan.dictionaryformids.hmi_android.data.TranslationsAdapter;
import de.kugihan.dictionaryformids.hmi_android.thread.LoadDictionaryThread;
import de.kugihan.dictionaryformids.hmi_android.thread.Translations;
import de.kugihan.dictionaryformids.hmi_android.view_helper.DialogHelper;
import de.kugihan.dictionaryformids.hmi_android.view_helper.TranslationScrollListener;
import de.kugihan.dictionaryformids.translation.TranslationParameters;
import de.kugihan.dictionaryformids.translation.TranslationParametersBatch;
import de.kugihan.dictionaryformids.translation.TranslationResult;

/*
 * Created by luo on 2016/4/4.
 */
public class SearchActivity extends Activity {
    private final static String TAG  = SearchActivity.class.getSimpleName();

    private Translations translations;
    private TranslationsAdapter translationsAdapter;
    private final DictionaryVector dictionaries = new DictionaryVector();
    private static final int MILLISECONDS_IN_A_SECOND = 1000;
    private LoadDictionaryThread loadDictionaryThread = null;
    private final Object loadDictionaryThreadSync = new Object();
    private final TranslationsObserver translationsObserver = new TranslationsObserver();
    private final TranslationScrollListener onScrollListener = new TranslationScrollListener();

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        // set up preferences
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        Preferences.attachToContext(getApplicationContext());

        // load theme before call to setContentView()
        setApplicationTheme();

        super.onCreate(savedInstanceState);
        setContentView(R.layout.search);

        findViewById(R.id.search).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startTranslation();
            }
        });



        // set preferred locale for application
        setCustomLocale(Preferences.getLanguageCode());

        translations = new Translations();
        translations.setExecutor(new DfMTranslationExecutor());

        // create the adapter to display translations
        final TranslationsAdapter translationsAdapter = new TranslationsAdapter(this);
        setTranslationAdapter(translationsAdapter);

        final ExpandableListView translationListView = (ExpandableListView) findViewById(R.id.translationsListView);
        translationListView.setAdapter(this.translationsAdapter);
//        translationListView.setOnFocusChangeListener(focusChangeListener);
        translationListView.setOnScrollListener(onScrollListener);
//        translationListView.setOnTouchListener(touchListener);
        translationListView.setOnGroupExpandListener(new ExpandableListView.OnGroupExpandListener() {
            @Override
            public void onGroupExpand(int groupPosition) {
                // Collapse all other groups
                for (int i = 0; i < translationListView.getExpandableListAdapter().getGroupCount(); i++) {
                    if (i != groupPosition && translationListView.isGroupExpanded(i)) {
                        translationListView.collapseGroup(i);
                    }
                }
            }
        });
        registerForContextMenu(translationListView);

        Util util = Util.getUtil();
        if (util instanceof AndroidUtil) {
            final AndroidUtil androidUtil = (AndroidUtil) util;
            androidUtil.setHandler(updateHandler);
        } else {
            util = new AndroidUtil(updateHandler);
            Util.setUtil(util);
        }

//        String zipPath = "/storage/emulated/0/DfM_Goldendict-Wordnet_EngDef.jar";
//        NativeZipInputStreamAccess inputStreamAccess;
//        inputStreamAccess = new NativeZipInputStreamAccess(zipPath);
//        startLoadDictionary(inputStreamAccess, DictionaryType.ARCHIVE,
//                zipPath);

        String filePath = "/storage/emulated/0/DictionaryForMIDs/dict/DfM_Goldendict-Wordnet_EngDef";
        startLoadDictionary(new FileDfMInputStreamAccess(filePath),
                DictionaryType.DIRECTORY, filePath);
    }

    /**
     * Start the thread to load a new dictionary and update the view.
     *
     * @param inputStreamAccess
     *            the input stream to load the dictionary
     * @param dictionaryType
     *            the type of the dictionary
     * @param dictionaryPath
     *            the path of the dictionary
     */
    private void startLoadDictionary(
            final DfMInputStreamAccess inputStreamAccess,
            final DictionaryType dictionaryType, final String dictionaryPath) {
        startLoadDictionary(inputStreamAccess, dictionaryType, dictionaryPath,
                null, false);
    }

    /**
     * Start the thread to load a new dictionary and update the view.
     *
     * @param inputStreamAccess
     *            the input stream to load the dictionary
     * @param dictionaryType
     *            the type of the dictionary
     * @param dictionaryPath
     *            the path of the dictionary
     * @param languageSelectionSet
     *            the selected language pairs
     * @param exitSilently
     *            true if the thread should not display dialogs
     */
    private void startLoadDictionary(
            final DfMInputStreamAccess inputStreamAccess,
            final DictionaryType dictionaryType, final String dictionaryPath,
            final Dictionary.LanguageSelectionSet languageSelectionSet, final boolean exitSilently) {

        if (isDictionaryLoaded(dictionaryType, dictionaryPath)) {
            // dictionary is already loaded
            if (!exitSilently) {
                // TODO: show toast
            }
            return;
        }

        // cancel running thread
        if (isLoadDictionaryThreadActive()) {
            synchronized (loadDictionaryThreadSync) {
                // TODO: handle multiple threads
            }
        }

        // check if results are shown or a dictionary is available
        if (translationsAdapter.hasData() || isDictionaryAvailable()) {
            // remove results from view
            translationsAdapter.clearData();
        }

        loadDictionaryThread = new LoadDictionaryThread();
        final LoadDictionaryThread.OnThreadResultListener threadListener =
                createThreadListener(dictionaryType, dictionaryPath, languageSelectionSet, exitSilently);
        loadDictionaryThread.setOnThreadResultListener(threadListener);
        loadDictionaryThread.execute(inputStreamAccess);
    }

    /**
     * Creates a listener for thread results.
     *
     * @param exitSilently
     *            true if the thread should exit silently
     * @return the thread result listener
     */
    private LoadDictionaryThread.OnThreadResultListener
    createThreadListener(final DictionaryType type, final String path,
                         final Dictionary.LanguageSelectionSet languageSelectionSet, final boolean exitSilently) {

        return new LoadDictionaryThread.OnThreadResultListener() {

            @Override
            public void onSuccess(DictionaryDataFile dataFile) {
                forgetThread();

                Dictionary activeDictionary = dictionaries.findMatchOrNull(type, path);
                if (activeDictionary != null) {
                    if (activeDictionary.getFile() != dataFile) {
                        activeDictionary.setFile(dataFile);
                    }
                    if (languageSelectionSet != null) {
                        languageSelectionSet.applyToDictionary(activeDictionary);
                    }
                    Log.i(TAG,"activeDictionary != null");
                } else {
                    Dictionary dictionary = new Dictionary(dataFile, type, path);
                    //TODO choose select pair
                    dictionary.setPairSelection(0, 1, true);

                    if (languageSelectionSet != null) {
                        languageSelectionSet.applyToDictionary(dictionary);
                    }
                    dictionaries.add(0, dictionary);
                    Preferences.addRecentDictionaryUrl(dictionary.getType(), dictionary.getPath(), dictionary.getLanguages());
                }

                Log.i(TAG,"onSuccess "+dictionaries.size());
            }

            @Override
            public void onInterrupted() {
                forgetThread();
                Log.e(TAG, "onInterrupted");
            }


            @Override
            public void onException(final DictionaryException exception,
                                    final boolean mayIncludeCompressedDictionary) {
                forgetThread();
                hideProgressBar();
                if (exitSilently) {
                    return;
                }
                if (mayIncludeCompressedDictionary) {
                    showDialogAndFail(DialogHelper.ID_WARN_EXTRACT_DICTIONARY);
                } else if (Preferences.isFirstRun()) {
                    showDialogAndFail(DialogHelper.ID_FIRST_RUN);
                } else {
                    showDialogAndFail(DialogHelper.ID_DICTIONARY_NOT_FOUND);
                }

                Log.e(TAG, "onException");
            }

            /**
             * Hides the progress bar. Can be called from non-UI threads.
             */
            private void hideProgressBar() {

            }

            /**
             * Shows the specified dialog in the UI and posts the onFailure
             * runnable.
             *
             * @param dialog
             *            the id of the dialog to show
             */
            private void showDialogAndFail(final int dialog) {

            }

            private void forgetThread() {
                synchronized (loadDictionaryThreadSync) {
                    if (isLoadDictionaryThreadActive()) {
                        loadDictionaryThread = null;
                    }
                }
            }
        };
    }

    private boolean isLoadDictionaryThreadActive() {
        return loadDictionaryThread != null;
    }

    /**
     * Checks if a dictionary is currently loaded
     *
     * @param dictionaryType the type of the dictionary
     * @param dictionaryPath the path of the dictionary
     * @return true if the dictionary is currently loaded
     */
    private boolean isDictionaryLoaded(DictionaryType dictionaryType, String dictionaryPath) {
        Dictionary dictionary = getLoadedDictionary(dictionaryType, dictionaryPath);
        return dictionary != null;
    }

    /**
     * Gets the instance of a dictionary if it has been loaded.
     *
     * @param dictionaryType the type of the dictionary
     * @param dictionaryPath the path of the dictionary
     * @return the instance of the dictionary or null if it has not yet been loaded
     */
    private Dictionary getLoadedDictionary(DictionaryType dictionaryType, String dictionaryPath) {
        if (dictionaries == null) {
            return null;
        }
        for (Dictionary dictionary : dictionaries) {
            if (dictionary.getFile() != null && dictionary.getType().ordinal() == dictionaryType.ordinal() && dictionary.getPath().equals(dictionaryPath)) {
                return dictionary;
            }
        }
        return null;
    }

    /**
     * Sets the locale of the current base context.
     *
     * @param languageCode
     *            the language code of the new locale
     */
    private void setCustomLocale(final String languageCode) {
        if (languageCode.length() == 0) {
            // use system default
            return;
        }
        setCustomLocale(languageCode, getBaseContext().getResources());
    }

    /**
     * Sets the locale of the given base context's resources.
     *
     * @param languageCode
     *            the language code of the new locale
     * @param resources
     *            the base context's resources
     */
    public static void setCustomLocale(final String languageCode,
                                       final Resources resources) {
        final Locale locale = getLocaleFromLanguageCode(languageCode);
        Locale.setDefault(locale);
        final Configuration config = new Configuration();
        config.locale = locale;
        resources.updateConfiguration(config, resources.getDisplayMetrics());
    }

    /**
     * Parses the given language code for language, country and variant and
     * creates a corresponding locale.
     *
     * @param languageCode
     *            the language code containing language-country-variant
     * @return the corresponding locale
     * @throws IllegalArgumentException
     *             if languageCode cannot be parsed
     */
    private static Locale getLocaleFromLanguageCode(final String languageCode)
            throws IllegalArgumentException {
        final String parts[] = languageCode.split("-");
        Locale locale;
        if (parts.length == 1) {
            locale = new Locale(languageCode);
        } else if (parts.length == 2) {
            locale = new Locale(parts[0], parts[1]);
        } else if (parts.length == 3) {
            locale = new Locale(parts[0], parts[1], parts[2]);
        } else {
            throw new IllegalArgumentException("languageCode contains "
                    + parts.length + ". Expected 1, 2 or 3");
        }
        return locale;
    }

    /**
     * Sets and registers a new TranslationAdapter to the activity to receive
     * updates.
     *
     * @param translationsAdapter
     *            the TranslationAdapter to use
     */
    public void setTranslationAdapter(final TranslationsAdapter translationsAdapter) {
        this.translationsAdapter = translationsAdapter;
        this.translationsAdapter.registerDataSetObserver(translationsObserver);
        this.translations.getTranslationState().addObserver(onFilterStateChangedObserver);
        this.translations.addObserver(translationsAdapter);
    }

    private class TranslationsObserver extends DataSetObserver {
        @Override
        public void onChanged() {
            super.onChanged();

            int resultCount = 0;
            for (TranslationResult translationResult : translationsAdapter.getTranslationResults()) {
                resultCount += translationResult.numberOfFoundTranslations();
            }

            Log.i(TAG, "result count=" + resultCount);
        }

        @Override
        public void onInvalidated() {
            super.onInvalidated();
            Log.i(TAG, "onInvalidated");
        }
    }

    /**
     * Observer to react on changes to the translation filter state.
     */
    private final Observer onFilterStateChangedObserver = new Observer() {
        @Override
        public void update(final Observable observable, final Object state) {
            boolean isFilterActive = (Boolean) state;
            if (isFilterActive){
                Log.i(TAG, "isFilterActive");
            }else {
                Log.i(TAG, "!=isFilterActive");
            }
        }
    };

    /**
     * Handler to process messages from non-GUI threads that need to interact
     * with the view.
     */
    private final Handler updateHandler = new Handler() {
        @Override
        public void handleMessage(final Message message) {
            switch (message.what) {
                case DictionaryForMIDs.THREAD_ERROR_MESSAGE:
                    handleTranslationThreadError(message);
                    break;
            }
            super.handleMessage(message);
        }

        private void handleTranslationThreadError(final Message message) {
            final String translationErrorMessage = getString(
                    R.string.msg_translation_error, (String) message.obj);

            Log.e(TAG, "translation error: "+translationErrorMessage);
        }
    };

    /**
     * Starts a translation if possible and updates the view.
     */
    private boolean startTranslation() {
        EditText queryET = (EditText) findViewById(R.id.query_word);
        String searchString = queryET.getText().toString();

        final StringBuffer searchWord = new StringBuffer(searchString);

        if (searchWord.length() == 0) {
            Toast.makeText(getBaseContext(), R.string.msg_enter_word_first,
                    Toast.LENGTH_LONG).show();
            return false;
        }
        if (!isDictionaryAvailable()) {
            Toast.makeText(getBaseContext(),
                    R.string.msg_load_dictionary_first, Toast.LENGTH_LONG)
                    .show();
            return false;
        }

        applySearchModeModifiers(searchWord);

        cancelActiveTranslation();

        // TODO: handle multiple dictionaries

        TranslationParametersBatch batchParameters = new TranslationParametersBatch();

        for (Dictionary dictionary : dictionaries) {
            final DictionaryDataFile file = dictionary.getFile();
            if (file == null) {
                continue;
            }

            for (int i = 0; i < file.supportedLanguages.length; i++) {
                for (int j = 0; j < file.supportedLanguages.length; j++) {
                    if (i == j || !dictionary.isPairSelected(i, j)) {
                        continue;
                    }

                    boolean[] inputLanguages = new boolean[file.supportedLanguages.length];
                    boolean[] outputLanguages = new boolean[file.supportedLanguages.length];

                    inputLanguages[i] = true;
                    outputLanguages[j] = true;

                    TranslationParameters translationParameters = new TranslationParameters(file,
                            searchWord.toString().trim(), inputLanguages, outputLanguages, true,
                            Preferences.getMaxResults(), Preferences.getSearchTimeout()
                            * MILLISECONDS_IN_A_SECOND);

                    batchParameters.addTranslationParameters(translationParameters);
                    Log.i(TAG, " addTranslationParameters");
                }
            }
        }
        Log.i(TAG,"start translation");
        translations.startTranslation(batchParameters);
        return true;
    }

    /**
     * Checks if there currently is a dictionary loaded and available for
     * searching.
     *
     * @return true if a dictionary is available
     */
    private boolean isDictionaryAvailable() {
        if (dictionaries.isEmpty()) {
            return false;
        }

        for (Dictionary dictionary : dictionaries) {
            if (dictionary.getFile() == null) {
                continue;
            }
            final boolean isLanguageAvailable = dictionary.getFile().numberOfAvailableLanguages > 0;
            if (isLanguageAvailable) {
                return true;
            }
        }

        return false;
    }

    /**
     * Apply search modifiers on the search word.
     *
     * @param searchWord
     *            the search input to apply the modifiers on
     */
    public static void applySearchModeModifiers(final StringBuffer searchWord) {
        if (hasSearchModifiers(searchWord)) {
            return;
        }

        if (searchWord.charAt(0) != Util.noSearchSubExpressionCharacter) {
            searchWord.insert(0, "" + Util.noSearchSubExpressionCharacter);
        }
        if (searchWord.charAt(searchWord.length() - 1) != Util.noSearchSubExpressionCharacter) {
            searchWord.append(Util.noSearchSubExpressionCharacter);
        }
    }

    /**
     * Checks if the given word includes search modifiers.
     *
     * @param searchWord
     *            the word to check
     * @return true if the word includes search modifiers, false otherwise
     */
    public static boolean hasSearchModifiers(final StringBuffer searchWord) {
        return searchWord.indexOf("" + Util.noSearchSubExpressionCharacter) >= 0
                || searchWord.indexOf("" + Util.wildcardAnySeriesOfCharacter) >= 0
                || searchWord.indexOf("" + Util.wildcardAnySingleCharacter) >= 0;
    }

    public void cancelActiveTranslation() {
        translations.cancelTranslation();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        // unregister observer for adapter as adapter is used in re-created
        // activity
        translationsAdapter.unregisterDataSetObserver(translationsObserver);
        translations.getTranslationState().deleteObserver(onFilterStateChangedObserver);
    }

    private void setApplicationTheme() {
        setApplicationTheme(this);
    }

    static void setApplicationTheme(final ContextThemeWrapper context) {
        final int theme = Preferences.getApplicationTheme();
        if (theme > 0) {
            context.setTheme(theme);
        }
    }

}
