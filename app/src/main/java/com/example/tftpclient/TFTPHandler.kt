package com.example.tftpclient

import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.*


const val SEND = 1
const val RECEIVED = 2
const val TFTP_PORT = 69
const val CHOOSE_FILE_CODE = 22
const val MODE_OCTET = "octet"
const val MODE_ASCII = "netascii"

class TFTPHandler {

    private var instance: TFTPHandler? = null

    private var sendFileDataStartPosition = 0
    private var sendFileDataEndPosition = 0
    private var isLastData = false
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


    fun getInstance(): TFTPHandler? {
        if (instance == null) {
            instance = TFTPHandler()
        }
        return instance
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



    /*******************
     **       WRQ      **
     * ******************/

    fun sendFileToServer(fileName: String, ipAddress: String, mode : String, bitmap: Bitmap, success: (result: ByteArray?) -> Unit, error: (result: ByteArray?) -> Unit) {
        //prepare for communication
        mInetAddress = InetAddress.getByName(ipAddress)
        mDatagramSocket = DatagramSocket()
        mFirstrequestByteArray = createRequest(WRQ, fileName, mode) //octet - binary
        mOutDatagramPacket = DatagramPacket(mFirstrequestByteArray, mFirstrequestByteArray.size, mInetAddress, TFTP_PORT)
        //send  WRQ request
        mDatagramSocket?.send(mOutDatagramPacket)
        Thread {
            sendFile(bitmap, success, error)
        }.start()
    }

    private fun sendFile(bitmap: Bitmap, success: (result: ByteArray?) -> Unit, error: (result: ByteArray?) -> Unit) {
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
                error.invoke(bufferByteArray)
                resetData()
            } else if (opCode[1] == ACK) {
                //TFTP packets block number
                val blockNumber = byteArrayOf(bufferByteArray[2], bufferByteArray[3])
                //send Image data to TFTP server
                sendData(blockNumber,bitmap)
            }
        } while (!isLastData)
        Log.d("SendFile","finish!!!: $block")
        mDatagramSocket?.close()
        resetData()
        success.invoke(bufferByteArray)
    }

    private fun sendData(blockNumber: ByteArray, bitmap: Bitmap) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
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
    fun getFileFromServer(fileName: String, ipAddress: String, mode: String, success: (result: ByteArrayOutputStream?, isError: Boolean) -> Unit, error: (result: ByteArray?) -> Unit) {
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
            error.invoke(null)
            resetData()
        }
        //receive file from TFTP server
        Thread {
            val byteOutOS = receiveFile(error)
            //write file to local disc
            success.invoke(byteOutOS, isError)
            isError = false
        }.start()

    }

    private fun receiveFile(error: (result: ByteArray?) -> Unit): ByteArrayOutputStream {
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
                isError = true
                error.invoke(bufferByteArray)
                resetData()
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
        return byteOutOS
    }



    private fun resetData() {
        sendFileDataStartPosition = 0
        sendFileDataEndPosition = 0
        blockNumberWrite = 1
        isLastData = false
        isFirstTime = true
    }

}