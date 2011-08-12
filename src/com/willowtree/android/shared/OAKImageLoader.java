package com.willowtree.android.shared;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.impl.client.DefaultHttpClient;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.github.droidfu.cachefu.ImageCache;
import com.github.droidfu.imageloader.ImageLoader;

public class OAKImageLoader extends ImageLoader implements Runnable {
	
	private static final String LOG_TAG = OAKImageLoader.class.getSimpleName();
	
	private String imageUrl;
	private String printedUrl; // imageUrl with transformation fingerprints prepended
	private OAKImageLoaderHandler handler;
	private ImageTransformation[] transformations;
	private static OAKImageCache imageCache;
	
	public static final int NO_DISK_CACHING = 0;
	public static final int INTERNAL_CACHING = 1;
	public static final int SD_CACHING = 2;
	public static final int PREFER_INTERNAL = 3;
	public static final int PREFER_SD = 4;
	
	private static Drawable defaultLoading = null;
	private static Drawable defaultError = null;
	public static boolean spinLoading = false;
	
	 /**
     * This method must be called before any other method is invoked on this class.
     * 
     * @param context
     *            the current context
     * @param cacheType
     * 			  What kind of disk caching, if any, should be used.<br>
     *			NO_DISK_CACHING: Use memory only.<br>
     *			INTERNAL_CACHING: Use device's internal memory.<br>
     *			SD_CACHING: Attempt to use an SD card for caching but use only memory if one isn't present.<br>
     *			PREFER_INTERNAL: Try to use internal memory, then fall back on SD, then fall back on memory only.<br>
     *			PREFER_SD: Try to use SD, then fall back on internal, then fall back on memory only.<br>
     */
    public static synchronized void initialize(Context context, int cacheType) {
        if (executor == null) {
            executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
        }
        if (imageCache == null) {
            imageCache = new OAKImageCache(25, expirationInMinutes, DEFAULT_POOL_SIZE);
            switch(cacheType) {
            case NO_DISK_CACHING:
            	break;
            case INTERNAL_CACHING:
            	imageCache.enableDiskCache(context, ImageCache.DISK_CACHE_INTERNAL);
            	break;
            case SD_CACHING:
            	imageCache.enableDiskCache(context, ImageCache.DISK_CACHE_SDCARD);
            	break;
            case PREFER_INTERNAL:
            	if(!imageCache.enableDiskCache(context, ImageCache.DISK_CACHE_INTERNAL))
            		imageCache.enableDiskCache(context, ImageCache.DISK_CACHE_SDCARD);
            	break;
            case PREFER_SD:
            	if(!imageCache.enableDiskCache(context, ImageCache.DISK_CACHE_SDCARD));
            		imageCache.enableDiskCache(context, ImageCache.DISK_CACHE_INTERNAL);
            	break;
            default:
            	break;
            }
            imageCache.updateContents();
        }
        
        if(defaultLoading == null){//initialize a spinner as the default loader
        	defaultLoading = new ProgressBar(context).getIndeterminateDrawable(); //get the spinner
        	spinLoading = true; //and set it to spin
        }
        
    }
    
	private OAKImageLoader(String imageUrl, String printedUrl, OAKImageLoaderHandler handler, ImageTransformation ... transformations) {
		super(imageUrl, handler);
		this.imageUrl = imageUrl;
		this.handler = handler;
		this.printedUrl = printedUrl;
		this.transformations = transformations;
	}
	
	
	public static void start(String imageUrl, OAKImageLoaderHandler handler) {
		start(imageUrl, handler.getImageView(), handler, null, null, new ImageTransformation[]{});
	}
	
	public static void start(String imageUrl, OAKImageLoaderHandler handler, ImageTransformation ... transformations) {
		start(imageUrl, handler.getImageView(), handler, null, null, transformations);
	}
	
	public static void start(String imageUrl, ImageView imageView, Drawable dummyDrawable,
			Drawable errorDrawable) {
		start(imageUrl, imageView, new OAKImageLoaderHandler(imageView, imageUrl), dummyDrawable,
				errorDrawable, new ImageTransformation[]{});
	}
	
	public static void start(String imageUrl, ImageView imageView, Drawable dummyDrawable,
			Drawable errorDrawable, ImageTransformation ... transformations) {
		start(imageUrl, imageView, new OAKImageLoaderHandler(imageView, imageUrl), dummyDrawable,
				errorDrawable, transformations);
	}
		
	public static void start(String imageUrl, ImageView imageView) {
		start(imageUrl, imageView, new OAKImageLoaderHandler(imageView, imageUrl), null, null, new ImageTransformation[]{});
	}
	
	public static void start(String imageUrl, ImageView imageView, ImageTransformation ... transformations) {
		start(imageUrl, imageView, new OAKImageLoaderHandler(imageView, imageUrl), null, null, transformations);
	}
	
	public static void start(String imageUrl, OAKImageLoaderHandler handler, Drawable dummyDrawable,
			Drawable errorDrawable, ImageTransformation ... transformations) {
		start(imageUrl, handler.getImageView(), handler, dummyDrawable, errorDrawable, transformations);
	}
	
	public static void start(String imageUrl, OAKImageLoaderHandler handler, Drawable dummyDrawable,
			Drawable errorDrawable) {
		start(imageUrl, handler.getImageView(), handler, dummyDrawable, errorDrawable,
				new ImageTransformation[]{});
	}
	
	protected static void start(String imageUrl, ImageView imageView, OAKImageLoaderHandler handler,
			Drawable dummyDrawable, Drawable errorDrawable, ImageTransformation ... transformations) {
		
		//Set up defaults that can be configured at startup...
		dummyDrawable = (dummyDrawable == null)?defaultLoading:dummyDrawable;
		errorDrawable = (errorDrawable == null)?defaultError:errorDrawable;
		
		String printedUrl = imageUrl;
		for(ImageTransformation trans : transformations) {
			printedUrl = trans.fingerprint() + printedUrl;
		}
		if (imageView != null) {
            if (imageUrl == null) {
                // In a ListView views are reused, so we must be sure to remove the tag that could
                // have been set to the ImageView to prevent that the wrong image is set.
                imageView.setTag(null);
                setLoading(imageView, dummyDrawable);
                return;
            }
            String oldImageUrl = (String) imageView.getTag();
            if (printedUrl.equals(oldImageUrl)) {
                // nothing to do
                return;
            } else {
                // Set the dummy image while waiting for the actual image to be downloaded.
                setLoading(imageView, dummyDrawable);
                imageView.setTag(printedUrl);
            }
        }

        if (imageCache.containsKeyInMemory(printedUrl)) {
            // do not go through message passing, handle directly instead
        	handler.setPrintedUrl(printedUrl);
        	try{
        		handler.handleImageLoaded(imageCache.getBitmap(printedUrl), null);
        	}catch(java.lang.OutOfMemoryError e){
        		Log.e(LOG_TAG, "Out of memory from OAK cache", e);
        	}
            
        } else {
            executor.execute(new OAKImageLoader(imageUrl, printedUrl, handler, transformations));
        }
	}
	
	@Override
	public void run() {
        // TODO: if we had a way to check for in-memory hits, we could improve performance by
        // fetching an image from the in-memory cache on the main thread
		Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        Bitmap bitmap = null;
		try{
        	bitmap = imageCache.getBitmap(this.printedUrl);
        }catch(java.lang.OutOfMemoryError e){//Ran out of memory while decoding...
        	try{
        		bitmap.recycle();
        	}catch(NullPointerException n){}
        	Log.e(LOG_TAG, "Out of memory from OAK cache", e);
        }

        //Decoding bitmap might not always work, so we may need to d/l again...
        for(int tries = 0; bitmap == null && tries <= numRetries; tries++){
        	bitmap = downloadImage();
        }

        // TODO: gracefully handle this case.
        notifyImageLoaded(this.printedUrl, bitmap);
    }
	
	@Override
	public void notifyImageLoaded(String url, Bitmap bitmap) {
		handler.setPrintedUrl(this.printedUrl);
		Message message = new Message();
        message.what = HANDLER_MESSAGE_ID;
        Bundle data = new Bundle();
        data.putString(IMAGE_URL_EXTRA, url);
        Bitmap image = bitmap;
        data.putParcelable(BITMAP_EXTRA, image);
        message.setData(data);

        handler.sendMessage(message);
	}
	

    // TODO: we could probably improve performance by re-using connections instead of closing them
    // after each and every download
	@Override
    protected Bitmap downloadImage() {
        int timesTried = 1;
        Bitmap image = null;
        while (timesTried <= numRetries) {
            try {
            	
                byte[] imageData = retrieveImageData();

                if (imageData != null) {
                	if (transformations.length == 0) {
                		imageCache.put(imageUrl, imageData);
                	} else {
                		// TODO: something more efficient?
                		Bitmap bm = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
                		for(ImageTransformation trans : transformations) {
                			bm = trans.transform(bm);
                		}
                		ByteArrayOutputStream bos = new ByteArrayOutputStream();
                		bm.compress(CompressFormat.JPEG, 75, bos);
                		bm.recycle();
                		imageData = bos.toByteArray();
                		imageCache.put(printedUrl, imageData);
                	}
                } else {
                    break;
                }
                image = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
                break;
            } catch (Throwable e) {
                Log.w(LOG_TAG, "download for " + imageUrl + " failed (attempt " + timesTried + ")");
                e.printStackTrace();
                SystemClock.sleep(DEFAULT_RETRY_HANDLER_SLEEP_TIME);
                timesTried++;
            }
        }

        return image;
    }
	
	private HttpClient getHttpClient() {
		return new DefaultHttpClient();
	}

	/**
	 * Uses a BufferedHttpEntity to write to a byte array,
	 * to ensure that the complete data is loaded.
	 * New version 8/11/11 by cceckman to try to fix 2.3 issues.
	 */
	protected byte[] retrieveImageData() throws IOException {

		HttpGet req = new HttpGet(imageUrl);
		HttpResponse resp = (HttpResponse)getHttpClient().execute(req);
		
		BufferedHttpEntity bufResponse = new BufferedHttpEntity(resp.getEntity());//buffer the response before it comes back...
	
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		bufResponse.writeTo(baos);
		return baos.toByteArray();
		
    }
	
	public static void clearCache() {
		imageCache.clear();
	}

	public static Drawable getDefaultLoading() {
		return defaultLoading;
	}

	/**
	 * Sets the default "dummy" drawable- to use while loading.
	 * @param defaultLoading
	 */
	public static void setDefaultLoading(Drawable defaultLoading) {
		OAKImageLoader.defaultLoading = defaultLoading;
	}

	public static Drawable getDefaultError() {
		return defaultError;
	}

	/**
	 * Sets the default error drawable- to use when there was an error in loading the image.
	 * @param defaultError the Drawable of the default error image.
	 */
	public static void setDefaultError(Drawable defaultError) {
		OAKImageLoader.defaultError = defaultError;
	}
	
	/**
	 * Gets a "spinning" animation to use with a loading dialog.
	 * Rotates at 1HZ and does not stop.
	 */
	public static void setSpinning(View v){
		RotateAnimation a = new RotateAnimation(0f, 360f, Animation.ABSOLUTE, v.getWidth()/2, Animation.ABSOLUTE, v.getHeight()/2);
		a.setInterpolator(new LinearInterpolator());
		a.setRepeatCount(Animation.INFINITE);
		a.setDuration(1);
		a.setStartTime(AnimationUtils.currentAnimationTimeMillis());
		
		v.setAnimation(a);
	}
	
	/**
	 * Sets an image in the loading state.
	 * @param v
	 * @param loading
	 */
	public static void setLoading(ImageView v, Drawable loading){
		v.setImageDrawable(loading);
        if(spinLoading){
        	setSpinning(v);
        }
        v.setVisibility(View.VISIBLE);
	}
	
}
