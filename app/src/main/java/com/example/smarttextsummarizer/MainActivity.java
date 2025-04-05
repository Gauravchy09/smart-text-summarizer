package com.example.smarttextsummarizer;

import android.content.*;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.*;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.*;

import java.io.*;
import java.util.Locale;

import okhttp3.*;

public class MainActivity extends AppCompatActivity {

    EditText inputText;
    RadioGroup formatGroup;
    SeekBar summaryLengthBar;
    TextView summaryLengthLabel, outputSummary;
    Button summarizeButton, copyButton, downloadButton, shareButton, ttsButton, pdfButton;

    // Replace with your actual API key and URL
    final String API_KEY = "YOUR_API_KEY";
    // Replace with your actual API URL
    final String API_URL = "YOUR_URL" + API_KEY;

    TextToSpeech textToSpeech;
    ActivityResultLauncher<Intent> pdfPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputText = findViewById(R.id.inputText);
        formatGroup = findViewById(R.id.formatGroup);
        summaryLengthBar = findViewById(R.id.summaryLengthBar);
        summaryLengthLabel = findViewById(R.id.summaryLengthLabel);
        outputSummary = findViewById(R.id.outputSummary);
        summarizeButton = findViewById(R.id.summarizeButton);
        copyButton = findViewById(R.id.copyButton);
        downloadButton = findViewById(R.id.downloadButton);
        shareButton = findViewById(R.id.shareButton);
        ttsButton = findViewById(R.id.ttsButton);
        pdfButton = findViewById(R.id.pdfButton);

        summarizeButton.setOnClickListener(v -> summarizeText());
        copyButton.setOnClickListener(v -> copyToClipboard());
        downloadButton.setOnClickListener(v -> saveToFile());
        shareButton.setOnClickListener(v -> shareText());
        ttsButton.setOnClickListener(v -> speakText());
        pdfButton.setOnClickListener(v -> openPdfPicker());

        summaryLengthBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                summaryLengthLabel.setText("Summary Length: " + progress + "%");
            }
            public void onStartTrackingTouch(SeekBar seekBar) {}
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        textToSpeech = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.ERROR)
                textToSpeech.setLanguage(Locale.US);
        });

        pdfPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        try {
                            extractTextFromPdf(uri);
                        } catch (IOException e) {
                            e.printStackTrace();
                            Toast.makeText(this, "Error reading PDF", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private void openPdfPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/pdf");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        pdfPickerLauncher.launch(intent);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void extractTextFromPdf(Uri uri) throws IOException {
        ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
        if (pfd != null) {
            PdfRenderer renderer = new PdfRenderer(pfd);
            StringBuilder allText = new StringBuilder();

            extractPageText(renderer, 0, new StringBuilder(), text -> {
                inputText.setText(text.toString());
            });

            renderer.close();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void extractPageText(PdfRenderer renderer, int pageIndex, StringBuilder collectedText, OnTextExtracted callback) {
        if (pageIndex >= renderer.getPageCount()) {
            callback.onExtracted(collectedText);
            return;
        }

        PdfRenderer.Page page = renderer.openPage(pageIndex);
        Bitmap bitmap = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        page.close();

        InputImage image = InputImage.fromBitmap(bitmap, 0);
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);


        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    collectedText.append(visionText.getText()).append("\n\n");
                    extractPageText(renderer, pageIndex + 1, collectedText, callback);
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "OCR failed", Toast.LENGTH_SHORT).show();
                });
    }

    interface OnTextExtracted {
        void onExtracted(StringBuilder text);
    }

    private void summarizeText() {
        String input = inputText.getText().toString().trim();
        if (input.isEmpty()) {
            Toast.makeText(this, "Enter some text!", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean isBullet = (formatGroup.getCheckedRadioButtonId() == R.id.bulletOption);
        int percent = summaryLengthBar.getProgress();
        String format = isBullet ? " as bullet points" : " in a paragraph";
        String prompt = "Summarize the following text" + format + " with around " + percent + "% length:\n" + input;

        try {
            JSONObject part = new JSONObject().put("text", prompt);
            JSONObject item = new JSONObject().put("parts", new JSONArray().put(part));
            JSONArray content = new JSONArray().put(item);
            JSONObject body = new JSONObject().put("contents", content);

            OkHttpClient client = new OkHttpClient();
            RequestBody requestBody = RequestBody.create(
                    body.toString(), MediaType.get("application/json"));
            Request request = new Request.Builder().url(API_URL).post(requestBody).build();

            client.newCall(request).enqueue(new Callback() {
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "API call failed", Toast.LENGTH_SHORT).show());
                }

                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        String responseBody = response.body().string();
                        try {
                            JSONObject obj = new JSONObject(responseBody);
                            String summary = obj
                                    .getJSONArray("candidates")
                                    .getJSONObject(0)
                                    .getJSONObject("content")
                                    .getJSONArray("parts")
                                    .getJSONObject(0)
                                    .getString("text");

                            runOnUiThread(() -> outputSummary.setText(summary));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    } else {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error: " + response.message(), Toast.LENGTH_SHORT).show());
                    }
                }
            });
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void copyToClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Summary", outputSummary.getText().toString());
        cm.setPrimaryClip(clip);
        Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show();
    }

    private void saveToFile() {
        String text = outputSummary.getText().toString();
        if (text.isEmpty()) return;
        try {
            File file = new File(getExternalFilesDir(null), "summary.txt");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(text.getBytes());
            fos.close();
            Toast.makeText(this, "Saved to " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void shareText() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_TEXT, outputSummary.getText().toString());
        intent.setType("text/plain");
        startActivity(Intent.createChooser(intent, "Share summary using"));
    }

    private void speakText() {
        String text = outputSummary.getText().toString();
        if (!text.isEmpty())
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    @Override
    protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }
}
