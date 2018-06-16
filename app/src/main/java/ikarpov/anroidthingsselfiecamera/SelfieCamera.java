package ikarpov.anroidthingsselfiecamera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Toast;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Date;

public class SelfieCamera {

    private static final String TAG = SelfieCamera.class.getSimpleName();

    // saved image size
    private static final int IMAGE_WIDTH = 640;
    private static final int IMAGE_HEIGHT = 480;

    // actual preview size
    private static final int PREVIEW_WIDTH = 640;
    private static final int PREVIEW_HEIGHT = 480;

    private Context appContext;

    // thread for preview streaming
    private HandlerThread previewBackgroundThread;
    private Handler previewBackgroundHandler;

    // thread for image saving
    private HandlerThread imageBackgroundThread;
    private Handler imageBackgroundHandler;

    // for still image capture
    private ImageReader imageReader;
    // for preview stream
    private TextureView cameraPreviewView;

    private CameraDevice cameraDevice;

    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession captureSession;

    // Firebase
    private FirebaseDatabase firebaseDatabase;
    private FirebaseStorage firebaseStorage;

    // android device identifier
    private String android_id;

    public SelfieCamera() {
        firebaseDatabase = FirebaseDatabase.getInstance();
        firebaseStorage = FirebaseStorage.getInstance();
    }

    public void openCamera(Context context, TextureView textureView) {
        appContext = context;

        android_id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        cameraPreviewView = textureView;

        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        String cameraId = getCameraId(cameraManager);

        initializeThreads();

        imageReader = ImageReader.newInstance(IMAGE_WIDTH, IMAGE_HEIGHT, ImageFormat.JPEG, 2);
        imageReader.setOnImageAvailableListener(onImageAvailableListener, imageBackgroundHandler);

        // Open the camera resource
        try {
            cameraManager.openCamera(cameraId, cameraStateCallback, previewBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access exception", e);
        }
    }

    public void takePicture() {
        toast("Taking picture");

        try {
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());

            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);

            CameraCaptureSession.CaptureCallback CaptureCallback
                    = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    Log.i(TAG, "Capture completed");
                    createPreviewSession();
                }
            };

            captureSession.stopRepeating();
            captureSession.abortCaptures();
            captureSession.capture(captureBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void closeCamera() {
        previewBackgroundThread.quitSafely();
        imageBackgroundThread.quitSafely();

        if (null != captureSession) {
            captureSession.close();
            captureSession = null;
        }
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
    }

    // there should be exactly one id usually in AndroidThings
    private String getCameraId(CameraManager cameraManager) {
        String[] camIds;
        try {
            camIds = cameraManager.getCameraIdList();
        } catch (CameraAccessException e) {
            Log.e(TAG, "No cameras found");
            return null;
        }
        Log.i(TAG, "Camera id " + camIds[0]);

        return camIds[0];
    }

    private void initializeThreads() {
        previewBackgroundThread = new HandlerThread("PreviewBackground");
        previewBackgroundThread.start();
        previewBackgroundHandler = new android.os.Handler(previewBackgroundThread.getLooper());

        imageBackgroundThread = new HandlerThread("ImageBackground");
        imageBackgroundThread.start();
        imageBackgroundHandler = new android.os.Handler(imageBackgroundThread.getLooper());
    }

    private CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice cd) {
            Log.i(TAG, "Camera device open");
            cameraDevice = cd;
            createPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice cd) {
            Log.i(TAG, "Camera device disconnected");
            cd.close();
            cameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cd, int error) {
            Log.e(TAG, "Camera device error: " + error);
            cd.close();
            cameraDevice = null;
        }
    };


    private void createPreviewSession() {
        try {
            SurfaceTexture surfaceTexture = cameraPreviewView.getSurfaceTexture();
            surfaceTexture.setDefaultBufferSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
            Surface previewSurface = new Surface(surfaceTexture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(previewSurface);

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {

                    @Override
                    public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                        Log.i(TAG, "Preview configured");
                        try {
                            captureRequestBuilder.set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_OFF
                            );

                            CaptureRequest captureRequest = captureRequestBuilder.build();
                            captureSession = cameraCaptureSession;
                            captureSession.setRepeatingRequest(captureRequest, null, previewBackgroundHandler);
                        } catch (CameraAccessException e) {
                            Log.e(TAG, e.getMessage());
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                        Log.e(TAG, "Preview configuration failed");
                    }
                },
                previewBackgroundHandler
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Listener for new camera images
     */
    private ImageReader.OnImageAvailableListener onImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Log.i(TAG, "Image available");

                    Image image = reader.acquireLatestImage();
                    // get image bytes
                    ByteBuffer imageBuf = image.getPlanes()[0].getBuffer();
                    final byte[] imageBytes = new byte[imageBuf.remaining()];
                    imageBuf.get(imageBytes);
                    image.close();

                    uploadImage(imageBytes);
                }
            };

    /**
     * Upload image data to Firebase
     */
    private void uploadImage(final byte[] imageBytes) {
        if (imageBytes != null) {
            final DatabaseReference log = firebaseDatabase.getReference("logs").push();
            final StorageReference imageRef = firebaseStorage.getReference().child(log.getKey());

            // upload image to storage
            final UploadTask task = imageRef.putBytes(imageBytes);
            task.addOnSuccessListener(new OnSuccessListener<UploadTask.TaskSnapshot>() {
                @Override
                public void onSuccess(UploadTask.TaskSnapshot taskSnapshot) {
                    toast("Image upload successful");
                    log.child("date").setValue(new Date().toString());
                    log.child("image").setValue(taskSnapshot.getDownloadUrl().toString());
                    log.child("android_id").setValue(android_id);
                }
            }).addOnFailureListener(new OnFailureListener() {
                @Override
                public void onFailure(@NonNull Exception e) {
                    toast("Unable to upload image to Firebase");
                    log.removeValue();
                }
            });
        }
    }

    private void toast(String text) {
        Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show();
    }
}
