package com.example.tftpclient

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.appcompat.widget.AppCompatButton
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.io.*
import android.graphics.Bitmap
import androidx.appcompat.widget.AppCompatTextView
import com.polyak.iconswitch.IconSwitch
import java.util.*

import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import com.libizo.CustomEditText
import android.os.StrictMode
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.airbnb.lottie.LottieAnimationView

const val SEND = 1
const val RECEIVED = 2
const val TFTP_PORT = 69
const val CHOOSE_FILE_CODE = 22
const val MODE_OCTET = "octet"
const val MODE_ASCII = "netascii"


class MainActivity : AppCompatActivity() {

    private lateinit var mButton: AppCompatButton
    private lateinit var mSwitch: IconSwitch
    private lateinit var mHeader: AppCompatTextView
    private lateinit var mAddFileButton: AppCompatButton
    private lateinit var mSelectedImage: AppCompatImageView
    private var mServerIpAddress: CustomEditText? = null
    private lateinit var mFileName: CustomEditText
    private var mImageToSend: Bitmap? = null
    private var mSendReceiveStatus = SEND
    private var sendFileDataStartPosition = 0
    private var sendFileDataEndPosition = 0
    private var isLastData = false
    lateinit var mLottieLoadingAnim: LottieAnimationView
    private var isFirstTime = true
    private var blockNumberWrite = 1
    private var isError = false
    private val zeroByte: Byte = 0

    /*  OPCODE
    *  1 - Read request (RRQ)
    *  2 - Write request (WRQ)
    *  3 - Data (DATA)
    *  4 - Acknowledgment (ACK)
    *  5 - Error (ERROR)
    */

    private val RRQ: Byte = 1
    private val WRQ: Byte = 2
    private val DATAPACKET: Byte = 3
    private val ACK: Byte = 4
    private val ERROR: Byte = 5

    private val PACKET_SIZE = 516

    private var mDatagramSocket: DatagramSocket? = null
    private var mInetAddress: InetAddress? = null
    private lateinit var mFirstrequestByteArray: ByteArray
    private lateinit var bufferByteArray: ByteArray
    private lateinit var mOutDatagramPacket: DatagramPacket
    private lateinit var mInDatagramPacket: DatagramPacket


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        setContentView(R.layout.activity_main)
        mButton = findViewById(R.id.button)
        mSwitch = findViewById(R.id.iconSwitch)
        mHeader = findViewById(R.id.header)
        mAddFileButton = findViewById(R.id.addfile)
        mSelectedImage = findViewById(R.id.selectedImage)
        mServerIpAddress = findViewById(R.id.ipaddress)
        mFileName = findViewById(R.id.filename)
        mLottieLoadingAnim = findViewById(R.id.loading)
        mLottieLoadingAnim.visibility = View.GONE
        initButton()
    }

    private fun initButton() {
        mSwitch.setCheckedChangeListener {
            when (it) {
                IconSwitch.Checked.RIGHT -> {
                    mSendReceiveStatus = RECEIVED
                    mSelectedImage.visibility = View.GONE
                    mAddFileButton.visibility = View.GONE
                    mSelectedImage.setImageBitmap(null)
                    mImageToSend = null
                    mButton.text = "Receive"
                    mHeader.text = "TFTP \n Receive"
                }
                IconSwitch.Checked.LEFT -> {
                    mSelectedImage.visibility = View.GONE
                    mSelectedImage.setImageBitmap(null)
                    mImageToSend = null
                    mSendReceiveStatus = SEND
                    mAddFileButton.visibility = View.VISIBLE
                    mButton.text = "Send"
                    mHeader.text = "TFTP \n Send"
                }
            }
        }

        mAddFileButton.setOnClickListener {
            addFileFromPhone()
        }

        mButton.setOnClickListener {
            if (validation()) {
                if (mSendReceiveStatus == SEND) {
                    mLottieLoadingAnim.visibility = View.VISIBLE
                    Thread(Runnable {
                        mImageToSend?.let {
                            sendFileToServer(mFileName.text.toString(), mServerIpAddress?.text.toString(), MODE_OCTET)
                        }
                    }).start()
                } else {
                    mLottieLoadingAnim.visibility = View.VISIBLE
                    mSelectedImage.visibility = View.GONE
                    mSelectedImage.setImageBitmap(null)
                    Thread(Runnable {
                        getFileFromServer(mFileName.text.toString(), mServerIpAddress?.text.toString(), MODE_OCTET)
                    }).start()
                }
            }
        }
    }

    private fun validation(): Boolean {
        if (mServerIpAddress?.text.isNullOrEmpty()) {
            Toast.makeText(this, "Please Enter Server IP", Toast.LENGTH_LONG).show();
            return false
        }
        if ((mServerIpAddress?.text?.matches("\\d+(\\.\\d+)+(\\.\\d+)+(\\.\\d+)+(\\.\\d+)?".toRegex())) == false) {
            Toast.makeText(this, "Please Enter valid IP", Toast.LENGTH_LONG).show();
            return false
        }
        if (mFileName.text.isNullOrEmpty()) {
            Toast.makeText(this, "Please Enter File Name", Toast.LENGTH_LONG).show();
            return false
        }
        if (mSendReceiveStatus == SEND && mImageToSend == null) {
            Toast.makeText(this, "Please select an image", Toast.LENGTH_LONG).show();
            return false
        }
        return true
    }


    /*******************
     **       WRQ      **
     * ******************/

    private fun sendFileToServer(fileName: String, ipAddress: String, mode : String) {
        //prepare for communication
        mInetAddress = InetAddress.getByName(ipAddress)
        mDatagramSocket = DatagramSocket()
        mFirstrequestByteArray = createRequest(WRQ, fileName, mode) //octet - binary
        mOutDatagramPacket = DatagramPacket(mFirstrequestByteArray, mFirstrequestByteArray.size, mInetAddress, TFTP_PORT)
        //send  WRQ request
        mDatagramSocket?.send(mOutDatagramPacket)
        sendFile()
    }

    private fun sendFile() {
        var block = 1
        do {
            Log.d("SendFile","packet count: $block")
            block++
            bufferByteArray = ByteArray(PACKET_SIZE)
            mDatagramSocket?.let {
                mInDatagramPacket = DatagramPacket(bufferByteArray, bufferByteArray.size, mInetAddress, it.localPort)
            }

            //receive packet from server
            mDatagramSocket?.receive(mInDatagramPacket)

            //first 4 characters from mInDatagramPacket - 2 byte OpCode , 2 byte BlockNumber
            val opCode = byteArrayOf(bufferByteArray[0], bufferByteArray[1])
            if (opCode[1] == ERROR) {
                runOnUiThread {
                    reportError()
                }
            } else if (opCode[1] == ACK) {
                //TFTP packets block number
                val blockNumber = byteArrayOf(bufferByteArray[2], bufferByteArray[3])
                //send Image data to TFTP server
                sendData(blockNumber)
            }
        } while (!isLastData)
        Log.d("SendFile","finish!!!: $block")
        mDatagramSocket?.close()
        runOnUiThread {
            resetData()
            mLottieLoadingAnim.visibility = View.GONE
            showPopUpSuccess()
        }
    }

    private fun sendData(blockNumber: ByteArray) {
        val stream = ByteArrayOutputStream()
        mImageToSend?.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val bitmapdata = stream.toByteArray()
        //send max of 512 byte each packet
        sendFileDataStartPosition = sendFileDataEndPosition
        if (bitmapdata.size - sendFileDataEndPosition > 512) {
            sendFileDataEndPosition += 512

        } else {
            sendFileDataEndPosition = bitmapdata.size
            isLastData = true
        }

        //build packet - 2 byte : OpCode, 2 byte : Block number , n byte (>= 512) : data
        val sendPartOfFile =
            Arrays.copyOfRange(bitmapdata, sendFileDataStartPosition, sendFileDataEndPosition)
        Log.d("SendData","$sendFileDataStartPosition-$sendFileDataEndPosition")
        val wrqByteLength: Int = 4 + sendPartOfFile.size
        val wrqByteArray = ByteArray(wrqByteLength)

        var position = 0
        wrqByteArray[position] = zeroByte
        position++
        wrqByteArray[position] = DATAPACKET
        position++

        //check if block number bigger than 2^8 (1 byte)
        if (blockNumberWrite.toByte() <= 256) {
            wrqByteArray[position] = blockNumber[0]
            position++
            wrqByteArray[position] = blockNumberWrite.toByte()
            blockNumberWrite++
        } else {
            wrqByteArray[position] = blockNumberWrite.toByte()
            blockNumberWrite++
        }
        position++

        //write data to packet
        for (i in sendPartOfFile.indices) {
            wrqByteArray[position] = sendPartOfFile.get(i)
            position++
        }

        //get new PORT from TFTP packet and send data packet
        val data = DatagramPacket(wrqByteArray, wrqByteArray.size, mInetAddress, mInDatagramPacket.port)
        try {
            mDatagramSocket?.send(data)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /*******************
     **       RRQ      **
     * ******************/
    private fun getFileFromServer(fileName: String, ipAddress: String, mode: String) {
        //prepare for communication
        mInetAddress = InetAddress.getByName(ipAddress)
        mDatagramSocket = DatagramSocket()
        mFirstrequestByteArray = createRequest(RRQ, fileName, mode)
        mOutDatagramPacket = DatagramPacket(
            mFirstrequestByteArray,
            mFirstrequestByteArray.size,
            mInetAddress,
            TFTP_PORT
        )

        //send RRQ request
        try {
            mDatagramSocket?.send(mOutDatagramPacket)
        } catch (e: IllegalArgumentException) {
            runOnUiThread {
                mLottieLoadingAnim.visibility = View.GONE
                showPopUpError("", e.message.toString())
            }
        }
        //receive file from TFTP server
        val byteOutOS = receiveFile()

        //write file to local disc
        writeFile(byteOutOS, fileName)
    }

    private fun receiveFile(): ByteArrayOutputStream {
        val byteOutOS = ByteArrayOutputStream()
        var block = 1
        do {
            Log.d("receiveFile","packet count: $block")
            block++
            bufferByteArray = ByteArray(PACKET_SIZE)
            mDatagramSocket?.let {
                mInDatagramPacket = DatagramPacket(bufferByteArray, bufferByteArray.size, mInetAddress, it.localPort)
            }
            mDatagramSocket?.receive(mInDatagramPacket)
            //first 2 characters from mInDatagramPacket - 2 byte OpCode
            val opCode = byteArrayOf(bufferByteArray[0], bufferByteArray[1])
            if (opCode[1] == ERROR) {
                runOnUiThread {
                    isError = true
                    reportError()
                }
            } else if (opCode[1] == DATAPACKET) {
                // Check for the TFTP packets block number
                val blockNumber = byteArrayOf(bufferByteArray[2], bufferByteArray[3])
                val dos = DataOutputStream(byteOutOS)
                dos.write(mInDatagramPacket.getData(), 4, mInDatagramPacket.getLength() - 4)

                //send ACK
                sendAcknowledgment(blockNumber)
            }
        } while (!(mInDatagramPacket.length < 512))
        mDatagramSocket?.close()
        Log.d("receiveFile","finish!!!: $block")
        runOnUiThread {
            if(!isError)
                showPopUpSuccess()
        }
        return byteOutOS
    }

    /******************************************
     **       Create WRQ or RRQ Request      **
     * ****************************************/
        // build packet - 2 byte : OpCode, n byte (>= 512) : file name , 1 byte : zero , "netascii (text) / octet (binary), 1 byte : zero
    private fun createRequest(opCode: Byte, fileName: String, mode: String): ByteArray {
        //calculate packet length
        val firstRequestPacketByteLength: Int = 2 + fileName.length + 1 + mode.length + 1
        val firstRequestPacketByteArray = ByteArray(firstRequestPacketByteLength)

        var position = 0
        firstRequestPacketByteArray[position] = zeroByte
        position++
        firstRequestPacketByteArray[position] = opCode
        position++
        for (i in fileName.indices) {
            firstRequestPacketByteArray[position] = fileName.get(i).code.toByte()
            position++
        }
        firstRequestPacketByteArray[position] = zeroByte
        position++
        for (i in mode.indices) {
            firstRequestPacketByteArray[position] = mode.get(i).code.toByte()
            position++
        }
        firstRequestPacketByteArray[position] = zeroByte
        return firstRequestPacketByteArray
    }

    /***********
     **  ACK  **
     **********/
    private fun sendAcknowledgment(blockNumber: ByteArray) {
        //build ack packet - 2 byte : OpCode , 2 byte - BlockNumber
        val zeroByte = 0
        val ackByteLength: Int = 4
        val ackByteArray = ByteArray(ackByteLength)
        var position = 0
        ackByteArray[position] = zeroByte.toByte()
        position++
        ackByteArray[position] = ACK
        position++
        ackByteArray[position] = blockNumber[0]
        position++
        ackByteArray[position] = blockNumber[1]

        // send acknowledgment
        val ack =
            DatagramPacket(ackByteArray, ackByteArray.size, mInetAddress, mInDatagramPacket.port)
        try {
            mDatagramSocket?.send(ack)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /***********
     **  ERROR  **
     **********/
    private fun reportError() {
        resetData()
        Log.e("Error","Get Error :(")
        val errorCode = String(bufferByteArray, 3, 1)
        val errorText = getErrorMessage(4, zeroByte, bufferByteArray)
        Log.e("Error", errorCode + " " +errorText)
        showPopUpError(errorCode, errorText)
        mLottieLoadingAnim.visibility = View.GONE
    }

    private fun getErrorMessage(at: Int, del: Byte,  message: ByteArray): String {
        var at = at
        val result = StringBuffer()
        while (message[at] != del) result.append(message[at++].toChar())
        return result.toString()
    }


    /***********
     **  UI  **
     **********/
    private fun writeFile(baoStream: ByteArrayOutputStream, fileName: String) {
        val path = filesDir
        val file = File(path, "/$fileName")
        FileOutputStream(file).use { outputStream ->
            baoStream.writeTo(outputStream)
        }
        runOnUiThread {
            val bitmap = BitmapFactory.decodeByteArray(
                baoStream.toByteArray(),
                0,
                baoStream.toByteArray().size
            )
            if (bitmap != null) {
                mSelectedImage.setImageBitmap(bitmap)
                mSelectedImage.visibility = View.VISIBLE
            }
            mLottieLoadingAnim.visibility = View.GONE
        }
    }

    private fun resetData() {
        sendFileDataStartPosition = 0
        sendFileDataEndPosition = 0
        blockNumberWrite = 1
        isLastData = false
        isFirstTime = true
    }

    private fun showPopUpError(errorCode: String, errorText: String) {
        val alertDialog = AlertDialog.Builder(this).create()
        var title = "Send File"
        if (mSendReceiveStatus == RECEIVED) {
            title = "Receive File"
        }
        alertDialog.setTitle(title)
        alertDialog.setMessage("Error: $errorCode $errorText")

        alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK")
        { dialog, which ->
            dialog.dismiss()
            isError = false
        }
        alertDialog.show()
    }

    private fun showPopUpSuccess() {
        val alertDialog = AlertDialog.Builder(this).create()
        var title = "Send"
        if (mSendReceiveStatus == RECEIVED) {
            title = "Receive"
        }
        alertDialog.setTitle(title)
        alertDialog.setMessage("Transfer Complete")

        alertDialog.setButton(
            AlertDialog.BUTTON_POSITIVE,
            "OK"
        ) { dialog, which -> dialog.dismiss() }
        alertDialog.show()
    }

    private fun addFileFromPhone() {
        var chooseFile = Intent(Intent.ACTION_GET_CONTENT)
        chooseFile.type = "image/*"
        chooseFile = Intent.createChooser(chooseFile, "Choose a file")
        startActivityForResult(chooseFile, CHOOSE_FILE_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            CHOOSE_FILE_CODE -> if (resultCode == RESULT_OK) {
                // Get the Uri of the selected file
                val _uri = data?.data
                val bitmap = MediaStore.Images.Media.getBitmap(this.contentResolver, _uri)
                if (bitmap != null) {
                    mSelectedImage.setImageBitmap(bitmap)
                    mImageToSend = bitmap
                    mSelectedImage.visibility = View.VISIBLE
                }
            }
        }
    }
}