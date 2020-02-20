package com.sarah.employeeapp;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.amazonaws.amplify.generated.graphql.CreateEmployeeMutation;
import com.amazonaws.amplify.generated.graphql.ListEmployeesQuery;
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient;
import com.amazonaws.mobileconnectors.appsync.fetcher.AppSyncResponseFetchers;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferObserver;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState;
import com.apollographql.apollo.GraphQLCall;
import com.apollographql.apollo.api.Response;
import com.apollographql.apollo.exception.ApolloException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import type.CreateEmployeeInput;

public class AddEmployeeActivity extends AppCompatActivity {
    private static final String TAG = AddEmployeeActivity.class.getSimpleName();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_employee);

        Button btnAddItem = findViewById(R.id.btn_save);
        btnAddItem.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View view) {
             uploadAndSave();
            }
        });

        Button btnAddPhoto = findViewById(R.id.btn_add_photo);
        btnAddPhoto.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View view) {
                choosePhoto();
            }
        });
    }

    private void save() {
        CreateEmployeeInput input = getCreateEmployeeInput();

        CreateEmployeeMutation addEmployeeMutation = CreateEmployeeMutation.builder()
                .input(input)
                .build();

        ClientFactory.appSyncClient().mutate(addEmployeeMutation).
                refetchQueries(ListEmployeesQuery.builder().build()).
                enqueue(mutateCallback);

        // Enables offline support via an optimistic update
        // Add to event list while offline or before request returns
        addEmployeeOffline(input);
    }
    private void addEmployeeOffline(final CreateEmployeeInput input) {

        final CreateEmployeeMutation.CreateEmployee expected =
                new CreateEmployeeMutation.CreateEmployee(
                        "Employee",
                        UUID.randomUUID().toString(),
                        input.name(),
                        input.description(),
                        input.photo());

        final AWSAppSyncClient awsAppSyncClient = ClientFactory.appSyncClient();
        final ListEmployeesQuery listEmployeesQuery = ListEmployeesQuery.builder().build();

        awsAppSyncClient.query(listEmployeesQuery)
                .responseFetcher(AppSyncResponseFetchers.CACHE_ONLY)
                .enqueue(new GraphQLCall.Callback<ListEmployeesQuery.Data>() {
                    @Override
                    public void onResponse(@Nonnull Response<ListEmployeesQuery.Data> response) {
                        List<ListEmployeesQuery.Item> items = new ArrayList<>();
                        if (response.data() != null) {
                            items.addAll(response.data().listEmployees().items());
                        }

                        items.add(new ListEmployeesQuery.Item(expected.__typename(),
                                expected.id(),
                                expected.name(),
                                expected.description(),
                                expected.photo()));
                        ListEmployeesQuery.Data data = new ListEmployeesQuery.Data(
                                new ListEmployeesQuery.ListEmployees("ModelEmployeeConnection", items, null));
                        awsAppSyncClient.getStore().write(listEmployeesQuery, data).enqueue(null);
                        Log.d(TAG, "Successfully wrote item to local store while being offline.");

                        finishIfOffline();
                    }

                    @Override
                    public void onFailure(@Nonnull ApolloException e) {
                        Log.e(TAG, "Failed to update event query list.", e);
                    }
                });
    }

    private void finishIfOffline(){
        // Close the add activity when offline otherwise allow callback to close
        ConnectivityManager cm =
                (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null &&
                activeNetwork.isConnectedOrConnecting();

        if (!isConnected) {
            Log.d(TAG, "App is offline. Returning to MainActivity .");
            finish();
        }
    }
    // Mutation callback code
    private GraphQLCall.Callback<CreateEmployeeMutation.Data> mutateCallback = new GraphQLCall.Callback<CreateEmployeeMutation.Data>() {
        @Override
        public void onResponse(@Nonnull final Response<CreateEmployeeMutation.Data> response) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(AddEmployeeActivity.this, "Added Employee", Toast.LENGTH_SHORT).show();
                    AddEmployeeActivity.this.finish();
                }
            });
        }

        @Override
        public void onFailure(@Nonnull final ApolloException e) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Log.e("", "Failed to perform AddEmployeeMutation", e);
                    Toast.makeText(AddEmployeeActivity.this, "Failed to add Employee", Toast.LENGTH_SHORT).show();
                    AddEmployeeActivity.this.finish();
                }
            });
        }
    };

    // Photo selector application code.
    private static int RESULT_LOAD_IMAGE = 1;
    private String photoPath;

    public void choosePhoto() {
        Intent i = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(i, RESULT_LOAD_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RESULT_LOAD_IMAGE && resultCode == RESULT_OK && null != data) {
            Uri selectedImage = data.getData();
            String[] filePathColumn = {MediaStore.Images.Media.DATA};
            Cursor cursor = getContentResolver().query(selectedImage,
                    filePathColumn, null, null, null);
            cursor.moveToFirst();
            int columnIndex = cursor.getColumnIndex(filePathColumn[0]);
            String picturePath = cursor.getString(columnIndex);
            cursor.close();
            // String picturePath contains the path of selected Image
            photoPath = picturePath;
        }
    }

    private String getS3Key(String localPath) {
        //We have read and write ability under the public folder
        return "public/" + new File(localPath).getName();
    }

    public void uploadWithTransferUtility(String localPath) {
        String key = getS3Key(localPath);

        Log.d(TAG, "Uploading file from " + localPath + " to " + key);

        TransferObserver uploadObserver =
                ClientFactory.transferUtility().upload(
                        key,
                        new File(localPath));

        // Attach a listener to the observer to get state update and progress notifications
        uploadObserver.setTransferListener(new TransferListener() {

            @Override
            public void onStateChanged(int id, TransferState state) {
                if (TransferState.COMPLETED == state) {
                    // Handle a completed upload.
                    Log.d(TAG, "Upload is completed. ");

                    // Upload is successful. Save the rest and send the mutation to server.
                    save();
                }
            }

            @Override
            public void onProgressChanged(int id, long bytesCurrent, long bytesTotal) {
                float percentDonef = ((float) bytesCurrent / (float) bytesTotal) * 100;
                int percentDone = (int)percentDonef;

                Log.d(TAG, "ID:" + id + " bytesCurrent: " + bytesCurrent
                        + " bytesTotal: " + bytesTotal + " " + percentDone + "%");
            }

            @Override
            public void onError(int id, Exception ex) {
                // Handle errors
                Log.e(TAG, "Failed to upload photo. ", ex);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(AddEmployeeActivity.this, "Failed to upload photo", Toast.LENGTH_LONG).show();
                    }
                });
            }

        });
    }
    private CreateEmployeeInput getCreateEmployeeInput() {
        final String name = ((EditText) findViewById(R.id.editTxt_name)).getText().toString();
        final String description = ((EditText) findViewById(R.id.editText_description)).getText().toString();

        if (photoPath != null && !photoPath.isEmpty()){
            return CreateEmployeeInput.builder()
                    .name(name)
                    .description(description)
                    .photo(getS3Key(photoPath)).build();
        } else {
            return CreateEmployeeInput.builder()
                    .name(name)
                    .description(description)
                    .build();
        }
    }


    private void uploadAndSave(){

        if (photoPath != null) {
            // For higher Android levels, we need to check permission at runtime
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                // Permission is not granted
                Log.d(TAG, "READ_EXTERNAL_STORAGE permission not granted! Requesting...");
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        1);
            }

            // Upload a photo first. We will only call save on its successful callback.
            uploadWithTransferUtility(photoPath);
        } else {
            save();
        }
    }
}