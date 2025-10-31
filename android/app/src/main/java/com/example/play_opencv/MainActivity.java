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
import android.hardware.camera2.CameraCharacteristics;
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

import com.example.play_opencv.databinding.ActivityMainBinding;

import java.util.List;
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "Camera";
    private static final int REQUEST_CAMERA_PERMISSION = 1001;

    private TextureView imageView;
    private CameraDevice.StateCallback cameraDeviceStateCallback;
    private CameraCaptureSession.StateCallback cameraCaptureSessionStateCallback;
    private CameraCaptureSession.CaptureCallback cameraCaptureSessionCaptureCallback;
    private ImageReader.OnImageAvailableListener imageReaderOnImageAvailableListener;
    private CameraDevice cameraDevice;

    private HandlerThread cameraBackgroundThread;
    private Handler cameraBackgroundHandler;
    private SurfaceTexture surfaceTexture;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest previewRequest;
    private CaptureRequest.Builder previewRequestBuilder;

    private ImageReader imageReader;
    // private ImageReader previewImageReader; // <-- REMOVED (Unused)
    private android.util.Size previewSize;     // <-- KEPT (This is now used)


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

        // Get camera characteristics to find supported YUV sizes
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(firstCameraId);
        android.hardware.camera2.params.StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        // For still image captures, we use the largest available size.
        assert map != null;

        // --- START: MODIFIED BLOCK ---
        // Ensure previewSize is set before we use it
        if (previewSize == null) {
            Log.e(TAG, "previewSize was not set (e.g., in onSurfaceTextureAvailable)");
            return;
        }

        Log.i(TAG, "Using preview size: " + previewSize.getWidth() + "x" + previewSize.getHeight());

        // Create the ImageReader with the TextureView's size
        imageReader = ImageReader.newInstance(
                previewSize.getWidth(),
                previewSize.getHeight(),
                android.graphics.ImageFormat.YUV_420_888,
                2 // maxImages
        );
        // --- END: MODIFIED BLOCK ---

        // Set the listener, running on our background handler
        imageReader.setOnImageAvailableListener(
                imageReaderOnImageAvailableListener,
                cameraBackgroundHandler
        );


        // Use a real executor
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

                // --- ADDED THIS LINE ---
                // Store the dimensions for the ImageReader
                previewSize = new android.util.Size(width, height);

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
                // For simplicity, we assume fixed size for this example
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
        imageReaderOnImageAvailableListener = reader -> {
            // Get the latest frame
            android.media.Image image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }

            // Get the Surface to draw on from our TextureView
            if (surfaceTexture != null) {
                Surface surface = new Surface(surfaceTexture);

                // Call the native function to process and draw
                processFrame(image, surface);

                // After this, the C++ code is responsible for drawing to the Surface.
                // We must close the image to let the next frame in.
                image.close();

                // Clean up the Surface object
                surface.release();
            } else {
                image.close();
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

                    // --- ADDED THIS LINE ---
                    // Re-initialize previewSize in case it was lost
                    previewSize = new android.util.Size(imageView.getWidth(), imageView.getHeight());

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
        // Also close the ImageReader
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    private void createCameraPreviewSession() throws CameraAccessException {
        // This function MUST have an initialized imageReader and cameraDevice
        if (imageReader == null || cameraDevice == null) {
            Log.e(TAG, "createCameraPreviewSession: imageReader or cameraDevice is null");
            return;
        }

        Surface imageReaderSurface = imageReader.getSurface(); // <-- GET IMAGEREADER SURFACE

        // Build the preview request
        previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

        // --- THIS IS THE KEY ---
        // The ONLY target is the ImageReader surface.
        // The C++ code will handle drawing to the TextureView surface.
        previewRequestBuilder.addTarget(imageReaderSurface);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            List<OutputConfiguration> outputConfigs =
                    java.util.Arrays.asList(
                            new OutputConfiguration(imageReaderSurface) // <-- ONLY output
                    );

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
                    java.util.Arrays.asList(imageReaderSurface), // <-- ONLY output
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
                    // Need to set previewSize here too, as onSurfaceTextureAvailable
                    // might have already run.
                    if (imageView.isAvailable()) {
                        previewSize = new android.util.Size(imageView.getWidth(), imageView.getHeight());
                        openCamera(imageView.getWidth(), imageView.getHeight());
                    }
                    // If not available, onSurfaceTextureAvailable will handle it.
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

    public native void processFrame(android.media.Image image, Surface surface);
}