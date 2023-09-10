package com.example.projectambilight

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.format.Formatter
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Timer
import java.util.TimerTask


class ForegroundService : Service() {

    private val TAG = "NACHOService"
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "ForegroundServiceChannel"

    private var idCounter = 0

    private var semaphore = Semaphore(1,1)

    private lateinit var surfaceScreen: Surface
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var mediaProjection: MediaProjection
    private lateinit var virtualDisplay: VirtualDisplay

    private lateinit var musicServerSocket:ServerSocket
    private lateinit var musicClientTCPSocket: Socket

    private lateinit var notificationInstance:Notification
    private lateinit var mImageReader:ImageReader

    private var mColorToSend = 0

    private var musicIp = ""
    private val musicPort = 50000

    private lateinit var tcpSocket:Socket

    public fun setIp(ipAddress:String)
    {
        musicIp = ipAddress
    }

    override fun onCreate() {
        super.onCreate()
        // Perform any initialization tasks here

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_HIGH
            )
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Configure a notification for the foreground service
        if (!this::notificationInstance.isInitialized)
        {
            notificationInstance = createNotification()
            // Start the service as a foreground service
            startForeground(NOTIFICATION_ID, notificationInstance, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        }

        if (intent != null)
        {
            when (intent.action)
            {
                "setUpVirtualScreen" -> {
                    musicIp = intent.getStringExtra("ipAddress").toString()
                    setUpVirtualScreen(intent.getIntExtra("requestCode", -1), intent.getIntExtra("resultCode", -1), intent.getParcelableExtra<Intent>("data")!!)
                }
                "startMusicServerThread" -> GlobalScope.launch(Dispatchers.IO) {
                    startMusicServerThread(intent.getStringExtra("tcpSocketIp"), intent.getIntExtra("tcpSocketPort", -1))
                }
            }
        }

        // Handle any tasks or requests here
        return START_STICKY
    }

    private fun createNotification(): Notification {
        // Create and return a notification for the foreground service
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ambilight")
            .setContentText("Running...")
            .setSmallIcon(R.drawable.widevibebanner)
            .setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)

        return notificationBuilder.build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private inner class ImageAvailableListener : ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader) {
            var image: Image? = null
            var resizedBitmap: Bitmap? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * resources.displayMetrics.widthPixels
                    // create bitmap
                    val bitmap = Bitmap.createBitmap(
                        resources.displayMetrics.widthPixels + rowPadding / pixelStride,
                        resources.displayMetrics.heightPixels,
                        Bitmap.Config.ARGB_8888
                    )

                    //fill from buffer
                    bitmap.copyPixelsFromBuffer(buffer)

                    mColorToSend = getDominantColor(bitmap)

                    sendRGBToSocket(mColorToSend)

                    bitmap.recycle()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                image?.close()
            }
        }
    }

    @SuppressLint("WrongConstant")
    fun setUpVirtualScreen(requestCode: Int, resultCode: Int, data: Intent) {

        //TODO: check if correct, might be causing problems
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // User granted permission, and you can now create a MediaProjection
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        // Now, you can use mediaProjection for screen capturing
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        var surfaceControlBuilder = SurfaceControl.Builder()
        surfaceControlBuilder.setBufferSize(screenWidth, screenHeight)
        surfaceControlBuilder.setFormat(PixelFormat.RGBA_8888)
        surfaceControlBuilder.setName("ScreenSaved")
        var surfaceControl = surfaceControlBuilder.build()
        surfaceScreen = Surface(surfaceControl)

        mImageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            mImageReader!!.surface, null, Handler(Looper.getMainLooper())
        )

        mImageReader!!.setOnImageAvailableListener(ImageAvailableListener(), Handler(Looper.getMainLooper()))
        semaphore.release()
    }

    private fun getLocalIPv4Address(c: Context): String? {
        val wifiManager = c.getSystemService(Context.WIFI_SERVICE) as WifiManager

        return Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        // Create a Bitmap with the correct dimensions
        val width = image.width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(width, image.height, Bitmap.Config.RGB_565)

        // Copy the pixel data from the Image into the Bitmap
        bitmap.copyPixelsFromBuffer(buffer)

        return bitmap
    }


    private fun getDominantColor(bitmap: Bitmap?): Int {
        //val newBitmap = Bitmap.createScaledBitmap(bitmap!!, 1, 1, true)
        val color = bitmap!!.getPixel(0, 0)
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        val alpha = Color.alpha(color)

        return getRGBValue(red, green, blue, alpha)
    }

    private fun getRGBValue(red: Int, green:Int, blue:Int, alpha:Int): Int {
        return (red * 65536) + (green * 256) + blue
    }

    private fun captureFrame() {
        // Process the image as a Bitmap
        try {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            var surfaceBitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.RGB_565)
        } catch (e: Exception)
        {
            Log.e(TAG, "Caught exception: $e")
        }
    }

    private fun sendRGB()
    {
        //Wait until music server settings are set up

        val timer = Timer()
        val captureIntervalMs = 200 // Adjust the frame rate as needed
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {

            }
        }, 0, captureIntervalMs.toLong())
    }

    inner class MusicServerThread : Thread() {
        override fun run() {
            try {
                musicServerSocket = ServerSocket()
                val musicSocketAddress = InetSocketAddress(musicIp, musicPort)
                musicServerSocket!!.bind(musicSocketAddress)
                Log.d(TAG, "Music Server listening on port $musicPort")

                GlobalScope.launch(Dispatchers.IO) {
                    musicClientTCPSocket = musicServerSocket!!.accept()
                }

                setUpMusicServerSettings()

                // Start the UDP broadcast
                GlobalScope.launch(Dispatchers.IO) {
                    sendRGB()
                }

            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    fun setUpMusicServerSettings()
    {
        // Create a JSON object for the command
        val input = JSONObject()
        input.put("id", idCounter++)
        input.put("method", "set_music")
        // Create a JSONArray for the "params" field
        val params = JSONArray()
        params.put(1)
        params.put(musicIp)
        params.put(musicPort)
        input.put("params", params)

        // Convert the JSON object to a string
        val commandStr = input.toString() + "\r\n"
        Log.d(TAG, "Input to TCP: $commandStr")

        // Send the command over the TCP socket
        val outputStream: OutputStream = tcpSocket.getOutputStream()
        outputStream.write(commandStr.toByteArray())

        // Receive and log the TCP response
        val response = ByteArray(1024)
        val bytesRead = tcpSocket.getInputStream().read(response)
        if (bytesRead != -1) {
            val responseStr = String(response, 0, bytesRead, Charsets.UTF_8)
            Log.d(TAG, "Received TCP response: $responseStr")
        }
        tcpSocket.close()
    }

    suspend fun startMusicServerThread(stringExtra: String?, intExtra: Int)
    {
        semaphore.acquire()
        tcpSocket = Socket(stringExtra, intExtra)

        var musicThread = MusicServerThread()
        musicThread.start()
    }

    private fun sendRGBToSocket(rgbValue:Int)
    {
        try {
            // Create a JSON object for the command
            val input = JSONObject()
            input.put("id", idCounter++)
            input.put("method", "set_rgb")
            // Create a JSONArray for the "params" field
            val params = JSONArray()
            params.put(rgbValue)
            params.put("smooth")
            params.put(200)
            input.put("params", params)

            // Convert the JSON object to a string
            val commandStr = input.toString() + "\r\n"

            // Send the command over the TCP socket
            val outputStream: OutputStream = musicClientTCPSocket.getOutputStream()
            outputStream.write(commandStr.toByteArray())

            Log.d(TAG, "Input to TCP: $commandStr")
        } catch (e: Exception) {
            Log.e(TAG, "Error communicating with the TCP server: ${e.message}")
        }
    }
}
