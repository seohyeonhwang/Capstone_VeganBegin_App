/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.sample.cloudvision;

import android.Manifest;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.vision.v1.Vision;
import com.google.api.services.vision.v1.VisionRequest;
import com.google.api.services.vision.v1.VisionRequestInitializer;
import com.google.api.services.vision.v1.model.AnnotateImageRequest;
import com.google.api.services.vision.v1.model.BatchAnnotateImagesRequest;
import com.google.api.services.vision.v1.model.BatchAnnotateImagesResponse;
import com.google.api.services.vision.v1.model.EntityAnnotation;
import com.google.api.services.vision.v1.model.Feature;
import com.google.api.services.vision.v1.model.Image;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;


public class MainActivity extends AppCompatActivity {
    private static final String CLOUD_VISION_API_KEY = "AIzaSyAsMHH0Xmksng2hafLnZuMLrxVnrkcOQYo";
    public static final String FILE_NAME = "temp.jpg";
    private static final String ANDROID_CERT_HEADER = "X-Android-Cert";
    private static final String ANDROID_PACKAGE_HEADER = "X-Android-Package";
    private static final int MAX_LABEL_RESULTS = 10;
    private static final int MAX_DIMENSION = 1200;

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int GALLERY_PERMISSIONS_REQUEST = 0;
    private static final int GALLERY_IMAGE_REQUEST = 1;
    public static final int CAMERA_PERMISSIONS_REQUEST = 2;
    public static final int CAMERA_IMAGE_REQUEST = 3;

    private TextView mImageDetails;
    private ImageView mMainImage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(view -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder
                    .setMessage(R.string.dialog_select_prompt)
                    .setPositiveButton(R.string.dialog_select_gallery, (dialog, which) -> startGalleryChooser())
                    .setNegativeButton(R.string.dialog_select_camera, (dialog, which) -> startCamera());
            builder.create().show();
        });

        mImageDetails = findViewById(R.id.image_details);
        mMainImage = findViewById(R.id.main_image);
    }


    public void startGalleryChooser() {
        if (PermissionUtils.requestPermission(this, GALLERY_PERMISSIONS_REQUEST, Manifest.permission.READ_EXTERNAL_STORAGE)) {
            Intent intent = new Intent();
            intent.setType("image/*");
            intent.setAction(Intent.ACTION_GET_CONTENT);
            startActivityForResult(Intent.createChooser(intent, "Select a photo"),
                    GALLERY_IMAGE_REQUEST);
        }
    }

    public void startCamera() {
        if (PermissionUtils.requestPermission(
                this,
                CAMERA_PERMISSIONS_REQUEST,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.CAMERA)) {
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            Uri photoUri = FileProvider.getUriForFile(this, getApplicationContext().getPackageName() + ".provider", getCameraFile());
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, CAMERA_IMAGE_REQUEST);
        }
    }

    public File getCameraFile() {
        File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return new File(dir, FILE_NAME);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == GALLERY_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            uploadImage(data.getData());
        } else if (requestCode == CAMERA_IMAGE_REQUEST && resultCode == RESULT_OK) {
            Uri photoUri = FileProvider.getUriForFile(this, getApplicationContext().getPackageName() + ".provider", getCameraFile());
            uploadImage(photoUri);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case CAMERA_PERMISSIONS_REQUEST:
                if (PermissionUtils.permissionGranted(requestCode, CAMERA_PERMISSIONS_REQUEST, grantResults)) {
                    startCamera();
                }
                break;
            case GALLERY_PERMISSIONS_REQUEST:
                if (PermissionUtils.permissionGranted(requestCode, GALLERY_PERMISSIONS_REQUEST, grantResults)) {
                    startGalleryChooser();
                }
                break;
        }
    }

    public void uploadImage(Uri uri) {
        if (uri != null) {
            try {
                // scale the image to save on bandwidth
                Bitmap bitmap =
                        scaleBitmapDown(
                                MediaStore.Images.Media.getBitmap(getContentResolver(), uri),
                                MAX_DIMENSION);

                callCloudVision(bitmap);
                mMainImage.setImageBitmap(bitmap);

            } catch (IOException e) {
                Log.d(TAG, "Image picking failed because " + e.getMessage());
                Toast.makeText(this, R.string.image_picker_error, Toast.LENGTH_LONG).show();
            }
        } else {
            Log.d(TAG, "Image picker gave us a null image.");
            Toast.makeText(this, R.string.image_picker_error, Toast.LENGTH_LONG).show();
        }
    }

    private Vision.Images.Annotate prepareAnnotationRequest(Bitmap bitmap) throws IOException {
        HttpTransport httpTransport = AndroidHttp.newCompatibleTransport();
        JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

        VisionRequestInitializer requestInitializer =
                new VisionRequestInitializer(CLOUD_VISION_API_KEY) {
                    /**
                     * We override this so we can inject important identifying fields into the HTTP
                     * headers. This enables use of a restricted cloud platform API key.
                     */
                    @Override
                    protected void initializeVisionRequest(VisionRequest<?> visionRequest)
                            throws IOException {
                        super.initializeVisionRequest(visionRequest);

                        String packageName = getPackageName();
                        visionRequest.getRequestHeaders().set(ANDROID_PACKAGE_HEADER, packageName);

                        String sig = PackageManagerUtils.getSignature(getPackageManager(), packageName);

                        visionRequest.getRequestHeaders().set(ANDROID_CERT_HEADER, sig);
                    }
                };

        Vision.Builder builder = new Vision.Builder(httpTransport, jsonFactory, null);
        builder.setVisionRequestInitializer(requestInitializer);

        Vision vision = builder.build();

        BatchAnnotateImagesRequest batchAnnotateImagesRequest =
                new BatchAnnotateImagesRequest();
        batchAnnotateImagesRequest.setRequests(new ArrayList<AnnotateImageRequest>() {{
            AnnotateImageRequest annotateImageRequest = new AnnotateImageRequest();

            // Add the image
            Image base64EncodedImage = new Image();
            // Convert the bitmap to a JPEG
            // Just in case it's a format that Android understands but Cloud Vision
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream);
            byte[] imageBytes = byteArrayOutputStream.toByteArray();

            // Base64 encode the JPEG
            base64EncodedImage.encodeContent(imageBytes);
            annotateImageRequest.setImage(base64EncodedImage);

            // add the features we want
            annotateImageRequest.setFeatures(new ArrayList<Feature>() {{
                Feature textDetection = new Feature();
                textDetection.setType("TEXT_DETECTION");
                textDetection.setMaxResults(MAX_LABEL_RESULTS);
                add(textDetection);
            }});

            // Add the list of one thing to the request
            add(annotateImageRequest);
        }});

        Vision.Images.Annotate annotateRequest =
                vision.images().annotate(batchAnnotateImagesRequest);
        // Due to a bug: requests to Vision API containing large images fail when GZipped.
        annotateRequest.setDisableGZipContent(true);
        Log.d(TAG, "created Cloud Vision request object, sending request");

        return annotateRequest;
    }

    private static class LableDetectionTask extends AsyncTask<Object, Void, String> {
        private final WeakReference<MainActivity> mActivityWeakReference;
        private Vision.Images.Annotate mRequest;

        LableDetectionTask(MainActivity activity, Vision.Images.Annotate annotate) {
            mActivityWeakReference = new WeakReference<>(activity);
            mRequest = annotate;
        }


        @Override
        protected String doInBackground(Object... params) {
            try {
                Log.d(TAG, "created Cloud Vision request object, sending request");
                BatchAnnotateImagesResponse response = mRequest.execute();
                return convertResponseToString(response);

            } catch (GoogleJsonResponseException e) {
                Log.d(TAG, "failed to make API request because " + e.getContent());
            } catch (IOException e) {
                Log.d(TAG, "failed to make API request because of other IOException " +
                        e.getMessage());
            }
            return "Cloud Vision API request failed. Check logs for details.";
        }

        protected void onPostExecute(String result) {
            MainActivity activity = mActivityWeakReference.get();
            if (activity != null && !activity.isFinishing()) {
                TextView imageDetail = activity.findViewById(R.id.image_details);
                imageDetail.setText(result);
            }
        }
    }

    private void callCloudVision(final Bitmap bitmap) {
        // Switch text to loading
        mImageDetails.setText(R.string.loading_message);

        // Do the real work in an async task, because we need to use the network anyway
        try {
            AsyncTask<Object, Void, String> labelDetectionTask = new LableDetectionTask(this, prepareAnnotationRequest(bitmap));
            labelDetectionTask.execute();
        } catch (IOException e) {
            Log.d(TAG, "failed to make API request because of other IOException " +
                    e.getMessage());
        }
    }

    private Bitmap scaleBitmapDown(Bitmap bitmap, int maxDimension) {

        int originalWidth = bitmap.getWidth();
        int originalHeight = bitmap.getHeight();
        int resizedWidth = maxDimension;
        int resizedHeight = maxDimension;

        if (originalHeight > originalWidth) {
            resizedHeight = maxDimension;
            resizedWidth = (int) (resizedHeight * (float) originalWidth / (float) originalHeight);
        } else if (originalWidth > originalHeight) {
            resizedWidth = maxDimension;
            resizedHeight = (int) (resizedWidth * (float) originalHeight / (float) originalWidth);
        } else if (originalHeight == originalWidth) {
            resizedHeight = maxDimension;
            resizedWidth = maxDimension;
        }
        return Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, false);
    }

    private static String convertResponseToString(BatchAnnotateImagesResponse response) {
        StringBuilder message = new StringBuilder();

        List<EntityAnnotation> labels = response.getResponses().get(0).getTextAnnotations();
        if (labels != null) {
            String total = labels.get(0).getDescription();
            String[] word = total.split(",|:|\\(|\\)|\n|\\s");

            for (int i = 0; i < word.length; i++) {
                message.append(word[i]);
                message.append("\n");
            }

        } else {
            message.append("nothing");
        }

        String meatList1 = "LA갈비,PORK OIL,돼지고기,젖소,육우,한우,소고기,젤라틴,케라틴,쇠고기,돈육,코치닐,카르민,셸락,락 색소,말뼈,말살,사골,소간,소뼈,소살,소위,소족,소피,암소,양간,양뼈,양족,우족,우황,토끼,황소,말고기,말지방,사슴뼈,사슴살,산양뿔,소가루,소갈비,소곱창,소꼬리,소내장,소단백,소대창,소등뼈,소등심,소막창,소머리,소목뼈,소목심,소방심,소사태,소선지,소설도,소스지,소안심,소양지,소연골,소염통,소우둔,소울대,소잡뼈,소잡육,소전각,소정육,소지라,소지방,소지육,소채끝,소허파,양갈비,양고기,양곱창,양내장,양대창,양정육,양지방,양지육,영양각,오겹살,척갈비,흑염소,냉동우육,냉장우육,말뼈분말,사골분말,사골육수,사슴고기,사슴육골,사슴정육,산양고기,소갈비뼈,소갈비살,소꽃갈비,소꾸리살,소다리살,소도가니,소뒷사태,소등갈비,소마구리,소머리뼈,소목심살,소무릎뼈,소보섭살,소본갈비,소부채살,소뼈분말,소뼈오일,소뼈육수,소살치살,소삼각살,소상박살,소설깃살,소안심살,소안창살,소앞다리,소앞사태,소업진살,소우둔살,소찜갈비,소참갈비,소채끝살,소치마살,소토시살,소혀고기,양고기살,양다리살,염소정육,염소지육,정제우지,토끼고기,경화소기름,당나귀고기,당나귀육골,로스팅우지,말뼈추출물,말뼈추출액,말태반분말,사골농축액,사골엑기스,사골추출물,사골추출액,사슴추출액,산토끼고기,소갈비덧살,소꽃등심살,소내장육수,소머리고기,소뭉치사태,소뼈농축액,소뼈엑기스,소뼈추출물,소뼈추출액,소아롱사태,소앞다리살,소앞치마살,소양지머리,소업진안살,소연골분말,소연골분말,소윗등심살,소정육분말,소정육분쇄,소제비추리,소차돌박이,소치마양지,소홍두깨살,양연골분말,양연골분말,양지추출물,정제소기름,당나귀추출물,말식용부산물,말태반추출물,사골조미분말,사골추출분말,소갈비추출액,소골수추출물,소꼬리농축액,소꼬리추출물,소부채덮개살,소뼈농축분말,소설깃머리살,라놀린,5'-이노신산이나트륨,소아래등심살,소양지추출액,소연골추출물,소연골추출물,소정육추출물,소정육추출액,흑염소추출물,사골농축엑기스,사골엑기스분말,사골추출농축액,사슴식용부산물,산양식용부산물,소도가니추출액,소뼈엑기스분말,소뼈추출농축액,소연골추출분말,소연골추출분말,양지효소분해액,염소식용부산물,당나귀식용부산물,산토끼식용부산물,양고기식용부산물,건조사슴식용부산물,사슴식용부산물추출물";
        List<String> meatList = Arrays.asList(meatList1.split(","));
        String chickenList1 = "치킨,거위,계육,거위간,계내금,꿩고기,메추리,칠면조,거위고기,거위지방,메추리고기,메추리정육,칠면조고기,칠면조다리,칠면조오일,꿩고기추출물,거위식용부산물,칠면조고기추출물,칠면조식용부산물,칠면조고기추출분말,닭,오리";
        List<String> chickenList = Arrays.asList(chickenList1.split(","));
        String milkList1 = "우유,젖산,Sterilization milk,Whole fat milk powder,저온살균 우유,Cultured milk,Degreased milk powder,우유단백가수분해물분말,마유,연유,원유,산양유,우유물,유지방,건조우유,고다치즈,살균우유,우유단백,우유분말,우유시럽,유청칼슘,정제우유,탈지우유,가공탈지분,농축유단백,농축유크림,분리유단백,산양유분말,에멘탈치즈,우유고형분,우유농축액,우유단백질,우유추출물,유단백분말,유지방분말,파마산치즈,가공유크림분,가공탈지분유,농축유단백질,무지유고형물,무지유고형분,우유추출농축,우유페이스트,유지혼합분말,조제탈지분유,탈지분유분말,탈지조제분유,탈지혼합분유,혼합탈지분유,분리유단백분말,유단백농축분말,초유단백분획물,혼합가공탈지분,혼합탈지유분말,혼합탈지유분말,혼합가공전지분유,가수분해유청단백질,카제인칼슘펩타이드,가공유지에스테르화유,가공유지에스테르화유,유단백가수분해물분말,milk sugar,락트알부민,마블쇼트닝,밀크칼슘,알파락트부민,요구르트 혼합,우유 단백 혼합분말,유단백가수분해물,유당,초유,카제인포스포펩타이드";
        List<String> milkList = Arrays.asList(milkList1.split(","));
        String fishList1 = "비타민D3,가재,갈돔,갈치,개불,검복,게살,고둥,곰치,구판,군소,굴비,금게,꺽지,꼬막,꼼치,꽁치,꽃게,꽃돔,낙지,날치,넙치,농어,누치,닭게,대게,대구,대복,대하,대합,덕대,도치,독돔,돌게,돌돔,동죽,뚝지,멍게,메기,멸치,명태,모려,문어,민어,민태,밀멸,밀복,박대,반지,방게,방어,배스,백합,뱅어,범게,범돔,병어,복섬,복어,볼락,부세,붉돔,붕어,빙어,뿔돔,삼치,상어,새우,새치,샛돔,샛멸,서대,성게,성대,소라,송어,수랑,숭어,싱어,쌀게,아귀,암치,양태,여어,연어,옥돔,왕게,우럭,웅어,은어,이리,잉어,자라,장어,재첩,적돔,전복,전어,조개,조기,졸복,준치,중하,쥐돔,쥐복,쥐치,집게,참게,참굴,참돔,참복,창난,청각,청돔,청멸,청어,초어,콩크,크릴,터봇,털게,토굴,토하,통치,파쿠,한치,해마,해삼,향어,혹돔,홍어,홍치,홍합,황돔,황복,황어,황태,황태,흑돔,가라지,가리비,가물치,가숭어,가시굴,가시복,가오리,가이양,가자미,가재살,각시돔,갈치살,감성돔,강달이,강담돔,강준치,개복치,개볼락,개상어,개서대,개조개,객주리,갯가재,갯고둥,갯농어,갯장어,거북복,거북손,건새우,건조굴,게껍질,게내장,게레치,게르치,게오일,게육수,게집게,고등어,곤쟁이,골뱅이,곱상어,구갈돔,구라미,국멸치,굴껍질,굴농축,굴분말,굴비살,굴비채,굴비포,굴원액,굴주스,귀상어,금눈돔,금색돔,기름치,긴갈돔,긴고둥,까나리,까치복,까칠복,꺼끌복,꺽정이,꼬리돔,꼴뚜기,꼽새돔,꽁치살,꽃새우,끈멍게,날쌔기,남생이,납작게,납지리,냉동게,냉동굴,넙치살,노래미,녹새치,녹줄돔,농조개,눈볼대,눈볼락,눈양태,눈퉁멸,능성어,다랑어,다슬기,달강어,달고기,달랑게,닭새우,대게살,대게알,대구간,대구살,대구위,대구포,대두어,대수리,댕가리,도다리,도루묵,도화돔,돔연육,동갈치,동미리,동사리,동자개,돛새치,둑중개,듀피시,등목어,떡조개,띠볼락,띠조개,만새기,말똥게,말새우,말전복,말쥐치,맛조개,망둥어,망상어,매리복,매퉁이,먹장어,멸치살,멸치액,멸치젓,명엽채,명태뼈,명태살,명태채,명태포,모오캐,물꽃치,물릉돔,물메기,물천구,물퉁돔,미거지,미더덕,미역치,민꽃게,민대구,민밀복,민새우,민챙이,밑성대,바위굴,바지락,밤고둥,밥새우,백련어,백미돔,백새치,밴댕이,뱀장어,뱅어포,버들치,범바리,베로치,벤자리,벵에돔,별넙치,별상어,별성대,별우럭,보구치,보리멸,복상어,부시리,불룩복,불볼락,붉감펭,붉바리,붉은맛,붉퉁돔,붉평치,붕메기,붕장어,블루길,비늘돔,빛조개,빨갱이,뿔소라,사자구,산천어,살살치,살조개,살홍어,삶은굴,삼세기,삼치살,상어살,상어유,새꼬막,새눈치,새다래,새뱅이,새우살,새우즙,새조개,샛줄멸,선홍치,성게살,소라살,송사리,송어살,수조기,실붉돔,쏘가리,쏠종개,쏨뱅이,쑤기미,쑥감팽,아귀간,아귀뼈,악상어,애꼬치,양미리,양볼락,양쥐돔,양태살,어름돔,여을멸,연어뼈,연어살,연어포,열빙어,염장게,염장굴,오징어,옥두어,왕연어,왕전복,용상어,용서대,위고둥,위소라,은대구,은띠복,은밀복,은상어,은샛돔,은연어,은장어,은줄멸,은행게,인상어,자라유,자리돔,자바리,자붉돔,자숙굴,자주복,자치복,잔멸치,장갱이,장어간,장어뼈,장어살,장어포,재첩살,잿방어,전갱이,전복살,점감펭,점넙치,점농어,점민어,점새우,점촉수,젓새우,정어리,젖새우,조갯살,조기살,조나게,좀볼락,주걱치,주꾸미,주둥치,줄갈돔,줄민태,줄삼치,중멸치,쥐어채,쥐치뼈,쥐치살,쥐치포,짱뚱어,쭈꾸미,찐대합,참돔살,참마자,참문어,참붕어,참서대,참재첩,참조기,참치살,참치액,참홍어,창꼬치,청대구,청새우,청새치,청어살,청어유,청황돔,칠서대,칼고기,칼상어,칼조개,큰눈돔,큰멸치,큰서대,키조개,타락치,톱상어,펄조개,평삼치,풀넙치,풀망둑,풀잉어,피라미,피조개,피홍합,하스돔,학공치,학꽁치,한볼락,해파리,핵꼬치,호박돔,홍감펭,홍대치,홍메기,홍민어,홍바리,홍살치,홍새우,홍서대,홍연어,홍옥치,홍합살,홍합액,황볼락,황새치,황아귀,황옥돔,황점복,황조어,황줄돔,황태살,황태살,황태채,황태채,황태포,흑대기,흑밀복,흑새치,흑조기,흰점복,히메치,가다랑어,가래상어,가리비살,가시망둑,가오리포,가자미살,가주넙치,각시붕어,각시서대,갈가자미,갈고등어,갈전갱이,갈치구이,갈치내장,갈치연육,갑오징어,강남상어,강도다리,개량조개,건다랑어,건새우액,건오징어,건조관자,건조대구,건조대합,건조멸치,건조명태,건조뱅어,건조연어,건조쥐치,건조참돔,건조참치,건조해삼,건조홍합,검복내장,검정대구,검정돌치,검정볼락,게농축액,게엑기스,게추출물,게추출액,고등어살,고등어포,고려홍어,곱사연어,광동홍어,괭이상어,괴도라치,구실우럭,구운꽁치,구운명태,구판분말,국매리복,국화조개,군평선이,굴농축액,굴엑기스,굴추출물,굴추출액,궁상퉁돔,귀오징어,귀판복갑,그물메기,극지대구,기름종개,긴가라지,까지양태,까치상어,까치횟대,깐새우살,깨알홍어,꼬리민태,꼬마달재,꼬마민어,꼬막육수,꼬치고기,꼬치삼치,꽁지양태,꽃개소겡,꽃게과립,꽃게오일,꾀붕장어,나일농어,나팔고둥,날개쥐치,날매퉁이,날치분말,남극크릴,남방대구,남방돗돔,납작소라,납작전어,냉동멸치,냉동새우,넙치껍질,네날가지,네동가리,노랑촉수,논우렁이,눈가자미,눈강달이,눈다랑어,눈동미리,눈불개복,눌치볼락,뉴지샛돔,다금바리,다랑어뼈,다랑어살,다랑어포,다슬기살,대게내장,대게분말,대구껍질,대구내장,대구머리,대구부레,대구분말,대구오일,대구횟대,대맛조개,대문바리,대왕바리,대합농축,대합분말,대합주스,도도바리,도화망둑,도화새우,도화양태,독가시치,돌가자미,돌기해삼,돌맛조개,돌묵상어,돌삼뱅이,동갈돗돔,동갈민어,동갈삼치,동갈양태,동갈횟대,동강연치,동남참게,동동갈치,동해담치,두줄촉수,두툽상어,둥근전복,드렁허리,등가시치,등갈민태,등줄숭어,딱총새우,땅가오리,마른한치,마찰넙치,매가오리,매물고둥,매지방어,메기분말,멸치볶음,멸치분말,멸치어유,멸치오일,멸치원액,멸치유탕,멸치육수,멸치조각,멸치조각,멸치조림,명주고둥,명태내장,명태연육,명태이리,모래무지,몽치다래,무늬갈돔,무늬바리,무늬퉁돔,무늬홍어,무태장어,묵꾀장어,문어가루,문어다리,문절망둑,물가자미,물레고둥,물치다래,미꾸라지,민달고기,민들조개,민물송어,민전갱이,밑달갱이,바다빙어,바다송어,바다장어,바닷가재,바지락살,배불뚝치,백다랑어,백상아리,백합조개,뱀장어간,버들붕어,벌레문치,범가자미,베도라치,별각시돔,보라성게,보리새우,보말고둥,복섬내장,복어껍질,볼기우럭,볼줄바리,부채새우,북방대합,분지성게,불가사리,불검퉁돔,불범상어,붉벤자리,붉은대게,붉은멍게,붉은메기,붕장어살,블랙피시,블루아이,블루피쉬,비늘백합,비늘삼치,비늘양태,비단고둥,비단조개,비막치어,비악상어,빛금눈돔,빨간대구,빨간양태,빨간횟대,빨강부치,빨판상어,뿔가자미,살꺽정이,살벤자리,살오징어,삶은꼬막,삶은새우,삶은참치,삼치구이,상어껍질,상어연골,새가라지,새우기름,새우농축,새우머리,새우분말,새우오일,새우조각,새우튀김,새치성게,선상연육,세줄볼락,솜털백합,쇠우렁이,수염대구,술전갱이,실꼬리돔,실전갱이,쌍동가리,쌍둥가리,쌍뿔달재,쏠배감펭,쏨뱅이류,아구상어,아귀어육,알락우럭,어린명태,어린명태,얼룩상어,얼룩활치,얼린명태,연어머리,연어병치,연어분말,연어비장,연어신선,연어오일,작은새우,장문볼락,장어구이,장어머리,장어오일,장어창자,재치조개,전갱이뼈,전갱이살,전갱이포,전복내장,전복분말,전어내장,절단꽃게,절단낙지,점가자미,점다랑어,점동갈돔,점양쥐돔,점줄우럭,접시조개,정어리살,정어리포,조개분말,조기구이,조기연육,조니퉁돔,조미멸치,조미새우,조미연어,조피볼락,졸복내장,종대우럭,종주둥치,주름백합,준치어육,줄가자미,줄노래미,줄도화돔,줄벤자리,줄비늘치,줄전갱이,쥐노래미,쥐복내장,쥐치어육,진주담치,진주조개,진홍퉁돔,쭈굴감펭,찰가자미,참가자미,염장갈치,염장낙지,염장멸치,염장새우,염장전어,염장청멸,오만둥이,오분자기,오징어귀,오징어살,오징어알,오징어입,오징어족,오징어채,오징어포,왕게붙이,왜주둥치,용가자미,우각바리,우럭구이,우럭볼락,유전갱이,유탕대합,으깬대구,은민대구,은붕장어,은어내장,은어분말,은장어포,이빨고기,이색장어,일본재첩,임연수어,잉어내장,자라고기,자색뿔돔,자숙새우,참꼴뚜기,참다랑어,참다슬기,참돔내장,참돔뱃살,참복내장,참조기살,참치기름,참치내장,참치농축,참치방어,참치분말,참치오일,참치주스,창꼴뚜기,창오징어,천전갱이,철갑둥어,철갑상어,청보리멸,청상아리,청색꽃게,청자갈치,청자고둥,청회볼락,체장메기,초록담치,총알고둥,칠레전복,칠성갈치,칠성상어,칠성장어,코코넛게,큰가리비,큰논우렁,큰눈퉁돔,큰은대구,탁자볼락,털보고둥,털수배기,털탑고둥,톱날꽃게,통의바리,투박조개,투어바리,튀긴황태,틸라피아,펄닭새우,페루멸치,메기,담치,크릴,정어리,참다랑어,흑돔,멸치,대게,삼치,오늬이마물맞이게,벌레무늬독가시치,해마,실꼬리돔,멍게,회초리꼬리민태,코드아이스피쉬,숭어,중간뿔물맞이게,어류,염장가이양내장,눈돔,오도리,빨강줄무늬집게,미꾸라지,다슬기,임연수,돌치,민어,대합,노랑줄꼬리양태,굴추출,긴발가락참집게,낙지,갈색무늬동미리,갈돔,흑점바리,타이어트랙일,토마토하인드,해파리,퓨질리어피쉬,필립흙무굴치,퓨실리아,큰얼룩통구멍,지브라타일돔,잔비늘매퉁이,전갱이,전복,바지락,전어,염장황강달이,영상가이석태,세네갈이석태,불가사리,등흑점옥두어,오분자기,맹그로브퉁돔,도다리,우렁이,노랑띠자붉돔,노랑촉수어육,나일틸라피아,문어,노랑각시서대,갈치,깃털제비활치,꼬리검정민태,꽃게,굴엑기스,굴자숙,굴주스,넙치,게껍질,게다리추,게엑기스,게육수,게추출,가시투성왕게,둥근바리,가물치,흑점날가지,베도라치,황줄깜정이,한벌홍감펭,털손참집게,파마이석태,퍼시픽도리,푸렁통구멍,카나리아돔,큰가시고기,큰꼬치고기,큰입선농어,청어,추사어름돔,동가리,참풀가사리,쥐치,쭈꾸미,조기,아귀,조미양태포,조미주둥치,점무늬암치,보리멸,복내장,장어,재첩,육색줄바리,은띠색줄멸,염장황석어,오렌지라피,오렌지퍼치,자리돔,양초선홍치,양태추출물,얼룩통구멍,성게,세네갈서대,시궁배톱치,시아멘시스,아담스백합,아케우스게,붉은이석태,붕어엑기스,블랙시바스,블루마오리,빨판매가리,뿔물맞이게,복어추출물,붉은쏨뱅이,별쭉지성대,병치매가리,보구치어육,동자개,우럭,잉어,민무늬백합,무늬양쥐돔,무레이코드,매퉁이연육,명엽채볶음,명태알분말,돼지가리맛,매퉁이어육,대안이석태,대왕범바리,던지네스게,동등이석태,단문청새치,대두이석태,대서양꽃돔,노랑벤자리,눈퉁멸분말,날치,남방달고기,대구,네줄벤자리,긴이마밤게,꼬리돔연육,굴페이스트,궁제기서대,긴가이석태,곧은뿔중하,구판추출액,굴껍질,굴농축,게살,게추출,고무꺽정이,게농축,건조농어위,가시실붉돔,가시이마쏙,가이양내장,가재,가시달강어,흑점샛돔,흙무굴치,가오리,꼴뚜기,가다랑어,가리비,볼락,참치,송어,연어,황강달이,놀래기,다랑어,황매퉁이,황적퉁돔,황태,해삼,가자미,새우,홍어,홍치어육,홍합,풀미역치,군소,퓨질리어,고둥,꽁치,조개,상어,가라지,오징어,고등어,골뱅이,자라,밴댕이,명태,날치추출물,지중해날치,대구뼈엑기스,대구뼈추출물,대구페이스트,점수염대구,대구간오일,대구아가미,대구엑기스,대구추출물,대서양대구,남방대구살,검정민대구,건조대구살,가이민대구,해덕대구,명태효소,건조명태,명태페이스트,얼린명태,명태아가미,명태엑기스,명태추출물,벨루가 철갑상어,상어간,상어연골,상어지느러미,러시안철갑상어,대서양철갑상어,흰배환도상어,꼬리기름상어,흑기흉상어,청새리상어,장완흉상어,삿징이상어,미흑점상어,모조리상어,극지별상어,가시줄상어,환도상어,행락상어,펜두상어,표범상어,건보리새우,꽃새우효소,아르헨티나붉은새우,새우페이스트,새우추출,가시투성어리새우,홍다리얼룩새우,줄무늬도화새우,북방가시배새우,새우분해농축액,새우살페이스트,새우엑기스,새우조미,네점발빨간새우,꽃새우건조,새우플레이크,새우오일,징거미새우,새우분말,석모자주새우,북쪽새우분말,삶은새우,자숙새우,가시자주새우,흰다리새우,중국젓새우,인도흰새우,새우농축액,바나나새우,무지개새우,돗대기새우,그라비새우,긴발줄새우,긴뿔민새우,광동줄새우,건보리새우,새우볶음,새우분태,각시흰새우,가시배새우,가시발새우,새우살,흰꼭지성게,성게추출물,무지개송어,훈제송어,연어난소막,연어이리,연어백자,연어비장,연어오일,연어추출,태평양연어,연어유,대서양연어,연어살,다뉴브연어,훈제연어,조기효소,조기단백질,조기추출물,흑조기,대서양조기,참치가수분해,참치건조,참치자숙,참치지느러미,참치뼈,참치오일,참치농축,참치엑기스,참치추출,참치펠리트,참치유,훈연참치,그물눈태평양청어,청어훈제,청어추출,가시복내장,황다랑어,다랑어액,날개다랑어,다랑어분말,다랑어뼈,다랑어액즙,다랑어포,다랑어살,훈연다랑어,다랑어농축,다랑어주스,다랑어오일,건조가다랑어,건조참다랑어,다랑어추출,다랑어엑기스,삶은참다랑어,다랑어내장,훈연참다랑어,훈연가다랑어,다랑어단백질,다랑어조미,다랑어유,다랑어펠리트";
        List<String> fishList = Arrays.asList(fishList1.split(","));
        String eggList1 = "게알,곤이,검복알,날치알,대구곤,대구알,명태알,복섬알,달걀,복어알,빙어란,상어알,새우알,성게알,송어알,연어알,조기알,졸복알,쥐복알,참복알,참치알,청어알,호키알,황복알,가시복알,거북복알,까치복알,까칠복알,다랑어알,매리복알,명태곤이,민밀복알,불룩복알,열빙어알,은밀복알,자주복알,계란,난백,난황,거위알,깐계란,난황유,난황칩,오리알,유정란,가당난황,가염난황,건조난백,건조난황,건조전란,계란과립,계란단백,계란볶음,계란분말,계란흰자,구운계란,난각분말,난각칼슘,난백단백,냉동난백,냉동난황,메추리알,살균난황,살균전란,삶은계란,액상계란,오리난황,전란단백,찐오리알,가당난황액,계란노른자,계란단백질,계란추출물,계란흰자액,깐메추리알,난각막분말,난백단백질,난황추출물,살균전란액,삶은오리알,전란단백질,건조계란흰자,건조난백분말,건조난황분말,건조전란분말,계란노른자액,계란플레이크,계란흰자분말,난각플레이크,난백단백분말,난백펩타이드,난황추출오일,난황펩타이드,메추리알조림,전란단백분말,건조계란노른자,계란노른자분말,계란단백농축분말,Whole egg liquid,액란,생계란,전란,스크램블드에그,난황레시틴,난각,캐비어추출물,철갑상어알,조미날치알,자라알분말,염장청어알,바다빙어알,건조청어알,건조명태알,흑밀복알,흰점복알,황점복알";
        List<String> eggList = Arrays.asList(eggList1.split(","));

        String[] level = {"논비건", "세미", "페스코", "락토오보", "락토", "비건"};
        

        int k = 0;
        ArrayList result = new ArrayList();
        while (k < 1) {
            for (int j = 0; j < meatList.size(); j++) {
                if (message.indexOf(meatList.get(j)) > -1) {
                    result.add(level[0]);
                    k++;
                    break;
                }
            }
            if (k == 0) {
                for (int m = 0; m < chickenList.size(); m++) {
                    if (message.indexOf(chickenList.get(m)) > -1) {
                        result.add(level[1]);
                        k++;
                        break;
                    }
                }
            }
            if (k == 0) {
                for (int n = 0; n < fishList.size(); n++) {
                    if (message.indexOf(fishList.get(n)) > -1) {
                        result.add(level[2]);
                        k++;
                        break;
                    }
                }
            }
            if (k == 0) {
                for (int b = 0; b < eggList.size(); b++) {
                    if (message.indexOf(eggList.get(b)) > -1) {
                        result.add(level[3]);
                        k++;
                        break;
                    }
                }
            }
            if (k == 0) {
                for (int v = 0; v < milkList.size(); v++) {
                    if (message.indexOf(milkList.get(v)) > -1) {
                        result.add(level[4]);
                        k++;
                        break;
                    }
                }
            }
            if (k == 0) {
                result.add(level[5]);
                k++;
                break;
            }
        }

        return result.toString()+" 용 음식입니다";
    }
}
