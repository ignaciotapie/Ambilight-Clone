package com.example.projectambilight

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.widget.Button
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket


class MainActivity : Activity() {
    private val musicServerSemaphore = Semaphore(1)
    private val mediaSemaphore = Semaphore(1)

    private val TAG = "NACHO"
    private var idCounter = 0

    private val sourcePort = 27000
    private var musicIp = ""
    private val musicPort = 50000
    private var deviceIp = ""
    private var devicePort = 0

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var tcpSocket:Socket? = null

    private lateinit var localIpAddress:String

    private lateinit var foregroundService: ForegroundService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        localIpAddress = getLocalIPv4Address(this)

        // Start an activity to request permission
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            1
        )

        GlobalScope.launch(Dispatchers.IO) {
            sendSsdpRequest()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1 && resultCode == Activity.RESULT_OK && data != null) {

            val foregroundServiceIntent = Intent(this, ForegroundService::class.java)
            foregroundServiceIntent.putExtra("requestCode", requestCode)
            foregroundServiceIntent.putExtra("resultCode", resultCode)
            foregroundServiceIntent.putExtra("data", data)
            foregroundServiceIntent.putExtra("ipAddress", localIpAddress)
            foregroundServiceIntent.action = "setUpVirtualScreen"
            startService(foregroundServiceIntent)
        }
    }


    private fun turnOnLights(tcpSocket: Socket, state:String) {
        try {
            // Create a JSON object for the command
            val input = JSONObject()
            input.put("id", idCounter++)
            input.put("method", "set_power")
            // Create a JSONArray for the "params" field
            val params = JSONArray()
            params.put(state)
            params.put("smooth")
            params.put(500)
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

            // Sleep for 0.5 seconds
            Thread.sleep(500)
        } catch (e: Exception) {
            Log.e(TAG, "Error communicating with the TCP server: ${e.message}")
        }
    }

    private fun getLocalIPv4Address(c: Context): String {
        val wifiManager = c.getSystemService(Context.WIFI_SERVICE) as WifiManager

        return Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
    }


    private fun sendSsdpRequest() {
        // The SSDP message to send
        val ssdpRequest = "M-SEARCH * HTTP/1.1\r\nHost: 239.255.255.250:1982\r\nMan: \"ssdp:discover\"\r\nST: wifi_bulb\r\n\r\n"
        if (localIpAddress != null) {
            musicIp = localIpAddress
            val localAddress = InetAddress.getByName(localIpAddress)

            val udpSocket = DatagramSocket(InetSocketAddress(localAddress, sourcePort))
            // Set the socket to broadcast mode
            udpSocket.broadcast = true

            // Send the SSDP request
            val ssdpPacket = DatagramPacket(ssdpRequest.toByteArray(), ssdpRequest.length, InetSocketAddress("239.255.255.250", 1982))
            udpSocket.send(ssdpPacket)

            // Receive and handle responses
            val buffer = ByteArray(1024)
            val udpPacket = DatagramPacket(buffer, buffer.size)

            udpSocket.receive(udpPacket)
            val data = String(buffer, 0, udpPacket.length)
            Log.d(TAG, "Received SSDP response: $data")
            udpSocket.close()

            handleSsdpResponse(data)
        } else {
            Log.e(TAG,"Failed to retrieve the local IP address.")
        }

    }

    fun extractLocation(httpResponse: String): String? {
        val locationRegex = Regex("Location: yeelight://(.+)")
        val matchResult = locationRegex.find(httpResponse)
        return matchResult?.groupValues?.get(1)
    }

    fun handleSsdpResponse(response: String) {
        // Extract Location header from SSDP response
        val location = extractLocation(response)

        if (location != null) {
            // Create a TCP connection based on the Location header
            try {
                deviceIp = location.substringBefore(":", "")
                devicePort = location.substringAfter(":", "").toInt()

                runOnUiThread {
                    val onButton = findViewById<Button>(R.id.onButton)
                    val offButton = findViewById<Button>(R.id.offButton)
                    val ambilightButton = findViewById<Button>(R.id.ambilightButton)
                    onButton.setOnClickListener {
                        GlobalScope.launch(Dispatchers.IO) {
                            if (tcpSocket == null) establishTcpConnection(deviceIp, devicePort)
                            if (tcpSocket != null)
                            {
                                turnOnLights(tcpSocket!!, "on")
                            }
                        }
                    }

                    offButton.setOnClickListener {
                        GlobalScope.launch(Dispatchers.IO) {
                            if (tcpSocket == null) establishTcpConnection(deviceIp, devicePort)
                            if (tcpSocket != null)
                            {
                                turnOnLights(tcpSocket!!, "off")
                            }
                        }
                    }

                    ambilightButton.setOnClickListener {
                        GlobalScope.launch(Dispatchers.IO) {
                            if (tcpSocket == null) establishTcpConnection(deviceIp, devicePort)
                            if (tcpSocket != null)
                            {
                                startAmbilight(tcpSocket!!)
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun establishTcpConnection(host: String, port: Int) {
        try {
            tcpSocket = Socket(host, port)


        } catch (e: Exception) {
            Log.e("YourTag", "Error establishing TCP connection: ${e.message}")
        }
    }

    fun startAmbilight(tcpSocket: Socket) {
        turnOnLights(tcpSocket, "on")

        val foregroundServiceIntent = Intent(this, ForegroundService::class.java)
        foregroundServiceIntent.action = "startMusicServerThread"
        foregroundServiceIntent.putExtra("tcpSocketIp", deviceIp)
        foregroundServiceIntent.putExtra("tcpSocketPort", devicePort)
        startService(foregroundServiceIntent)

    }
}
