package com.example.tftpclient

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.appcompat.widget.AppCompatButton
import java.io.*
import android.graphics.Bitmap
import androidx.appcompat.widget.AppCompatTextView
import com.polyak.iconswitch.IconSwitch

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
    lateinit var mLottieLoadingAnim: LottieAnimationView
    private val zeroByte: Byte = 0


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
                    mButton.text = this.getString(R.string.recive_button)
                    mHeader.text = this.getString(R.string.recive_header)
                }
                IconSwitch.Checked.LEFT -> {
                    mSelectedImage.visibility = View.GONE
                    mSelectedImage.setImageBitmap(null)
                    mImageToSend = null
                    mSendReceiveStatus = SEND
                    mAddFileButton.visibility = View.VISIBLE
                    mButton.text = this.getString(R.string.send_button)
                    mHeader.text = this.getString(R.string.send_header)
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
                    mImageToSend?.let {
                        //sendFileToServer(mFileName.text.toString(), mServerIpAddress?.text.toString(), MODE_OCTET)
                        TFTPHandler().getInstance()?.sendFileToServer(mFileName.text.toString(),
                            mServerIpAddress?.text.toString(),
                            MODE_OCTET,
                            it,
                            success = {
                                runOnUiThread {
                                    mLottieLoadingAnim.visibility = View.GONE
                                    showPopUpSuccess()
                                }
                            },
                            error = { errorByte ->
                                errorByte?.let {
                                    runOnUiThread {
                                        reportError(it)
                                    }
                                }
                            })
                    }
                } else {
                    mLottieLoadingAnim.visibility = View.VISIBLE
                    mSelectedImage.visibility = View.GONE
                    mSelectedImage.setImageBitmap(null)
                    TFTPHandler().getInstance()?.getFileFromServer(mFileName.text.toString(),
                        mServerIpAddress?.text.toString(),
                        MODE_OCTET,
                        success = { writeByte , isError ->
                            writeByte?.let {
                                runOnUiThread {
                                    if(!isError) {
                                        writeFile(it, mFileName.text.toString())
                                        showPopUpSuccess()
                                    }
                                }
                            }
                        },
                        error = {
                            runOnUiThread {
                                if (it == null) {
                                    showPopUpError()
                                } else {
                                    reportError(it)
                                }
                            }
                        })
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

    /***********
     **  ERROR  **
     **********/
    private fun reportError(bufferByteArray: ByteArray) {
        Log.e("Error", "Get Error :(")
        val errorCode = String(bufferByteArray, 3, 1)
        val errorText = getErrorMessage(4, zeroByte, bufferByteArray)
        Log.e("Error", "$errorCode $errorText")
        showPopUpError(errorCode, errorText)
        mLottieLoadingAnim.visibility = View.GONE
    }

    private fun getErrorMessage(at: Int, del: Byte, message: ByteArray): String {
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

    private fun showPopUpError(errorCode: String = "", errorText: String = "") {
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