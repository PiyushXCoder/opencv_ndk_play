package com.example.play_opencv;

// Import all necessary classes
import static android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;

import com.example.play_opencv.databinding.ActivityMainBinding;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "Camera";
    private static final int REQUEST_CAMERA_PERMISSION = 1001;

    private TextureView imageView;
    private CameraDevice.StateCallback cameraDeviceStateCallback;
    private CameraCaptureSession.StateCallback cameraCaptureSessionStateCallback;
    private CameraCaptureSession.CaptureCallback cameraCaptureSessionCaptureCallback;
    private CameraDevice cameraDevice;

    private HandlerThread cameraBackgroundThread;
    private Handler cameraBackgroundHandler;
    private SurfaceTexture surfaceTexture;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest previewRequest;
    private CaptureRequest.Builder previewRequestBuilder;


    // Used to load the 'play_opencv' library on application startup.
    static {
        System.loadLibrary("play_opencv");
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private void openCamera(int width, int height) throws Exception {
        if (cameraBackgroundHandler == null) {
            Log.e(TAG, "Background handler not initialized.");
            return;
        }

        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String firstCameraId = cameraManager.getCameraIdList()[0];

        Executor executor = (command) -> cameraBackgroundHandler.post(command);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cameraManager.openCamera(firstCameraId, executor, cameraDeviceStateCallback);
        } else {
            // Use the deprecated version for older APIs
            cameraManager.openCamera(firstCameraId, cameraDeviceStateCallback, cameraBackgroundHandler);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        imageView = binding.imageView;

        imageView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @SuppressLint("MissingPermission")
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                // Store the texture and set its size
                surfaceTexture = surface;
                surface.setDefaultBufferSize(width, height);

                Context context = imageView.getContext();

                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    try {
                        openCamera(width, height);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    if (context instanceof Activity) {
                        ActivityCompat.requestPermissions(
                                (Activity) context,
                                new String[]{Manifest.permission.CAMERA},
                                REQUEST_CAMERA_PERMISSION
                        );
                    } else {
                        Log.e(TAG, "Context is not an Activity — can't request permission");
                    }
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                // Important: return true
                return true;
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                // Can be used to reconfigure preview size if needed
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                // Called every time a new frame is available
            }
        });

        cameraDeviceStateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Log.w(TAG, "Camera disconnected");
                camera.close();
                cameraDevice = null;
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Log.e(TAG, "Camera error: " + error);
                camera.close();
                cameraDevice = null;
            }

            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                Log.i(TAG, "Camera device opened");
                cameraDevice = camera;
                // Now that the camera is open, create the capture session
                try {
                    createCameraPreviewSession();
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Failed to create preview session", e);
                }
            }
        };

        cameraCaptureSessionStateCallback = new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                Log.e(TAG, "Capture session configuration failed");
            }

            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                if (cameraDevice == null) {
                    return; // Camera is already closed
                }

                // Store the session
                cameraCaptureSession = session;
                Log.i(TAG, "Capture session configured");

                try {
                    // Set auto-focus, auto-exposure, etc. for the preview
                    previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                    // Build the final request
                    previewRequest = previewRequestBuilder.build();

                    // Finally, start displaying the camera preview.
                    session.setRepeatingRequest(
                            previewRequest,
                            cameraCaptureSessionCaptureCallback,
                            cameraBackgroundHandler
                    );
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Failed to set repeating request", e);
                }
            }
        };

        cameraCaptureSessionCaptureCallback = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                super.onCaptureCompleted(session, request, result);
            }

            @Override
            public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
                super.onCaptureProgressed(session, request, partialResult);
            }
        };

        // This is where you would handle a captured image
        ImageReader.OnImageAvailableListener imageReaderOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                // This is where you would handle a captured image
            }
        };
    }

    // --- Added Lifecycle and Helper Methods ---

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();

        // When the screen is turned off and back on, the SurfaceTexture is already
        // available, and onSurfaceTextureAvailable() will not be called.
        // In that case, we can open a camera here.
        if (imageView.isAvailable()) {
            try {
                // We need to re-check permission here for the onResume case
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    surfaceTexture = imageView.getSurfaceTexture(); // Get the existing texture
                    openCamera(imageView.getWidth(), imageView.getHeight());
                }
                // If permission is not granted, the listener will handle it
                // or the user will be prompted again.
            } catch (Exception e) {
                Log.e(TAG, "Failed to open camera onResume", e);
            }
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    private void startBackgroundThread() {
        cameraBackgroundThread = new HandlerThread("CameraBackground");
        cameraBackgroundThread.start();
        cameraBackgroundHandler = new Handler(cameraBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (cameraBackgroundThread != null) {
            cameraBackgroundThread.quitSafely();
            try {
                cameraBackgroundThread.join();
                cameraBackgroundThread = null;
                cameraBackgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "InterruptedException in stopBackgroundThread", e);
            }
        }
    }

    private void closeCamera() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private void createCameraPreviewSession() throws CameraAccessException {
        if (surfaceTexture == null) {
            Log.e(TAG, "SurfaceTexture is null, cannot create session");
            return;
        }
        if (cameraDevice == null) {
            Log.e(TAG, "CameraDevice is null, cannot create session");
            return;
        }

        Surface surface = new Surface(surfaceTexture);

        // Build the preview request
        previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        previewRequestBuilder.addTarget(surface); // Add the TextureView's surface as a target

        // Create the session
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            List<OutputConfiguration> outputConfigs =
                    Collections.singletonList(new OutputConfiguration(surface));

            Executor executor = (command) -> cameraBackgroundHandler.post(command);

            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SESSION_REGULAR,
                    outputConfigs,
                    executor,
                    cameraCaptureSessionStateCallback
            );
            cameraDevice.createCaptureSession(sessionConfig);
        } else {
            // Deprecated version for < API 28
            cameraDevice.createCaptureSession(
                    Collections.singletonList(surface),
                    cameraCaptureSessionStateCallback,
                    cameraBackgroundHandler
            );
        }
    }

    // Handle permission request result
    @SuppressLint("MissingPermission")
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission was granted, open the camera
                try {
                    openCamera(imageView.getWidth(), imageView.getHeight());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else {
                // Permission was denied. You can show a message to the user.
                Log.e(TAG, "Camera permission denied");
            }
        }
    }

    // --- End Added Methods ---

    /**
     * A native method that is implemented by the 'play_opencv' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();
}