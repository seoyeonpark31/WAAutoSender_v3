package com.waauto

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 메이크업 (빠른전송).
 * 저장 없이 붙여넣은 번호로 즉시 전송.
 */
class MakeupActivity : AppCompatActivity() {

    private lateinit var etLinks: EditText
    private lateinit var etMessage: EditText
    private lateinit var btnExtract: Button
    private lateinit var btnShowNumbers: Button
    private lateinit var btnSend: Button
    private lateinit var layoutExtractResult: LinearLayout
    private lateinit var tvExtractResult: TextView

    private var extractedPhones: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_makeup)

        supportActionBar?.title = "메이크업 (빠른전송)"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        etLinks             = findViewById(R.id.etLinks)
        etMessage           = findViewById(R.id.etMessage)
        btnExtract          = findViewById(R.id.btnExtract)
        btnShowNumbers      = findViewById(R.id.btnShowNumbers)
        btnSend             = findViewById(R.id.btnSend)
        layoutExtractResult = findViewById(R.id.layoutExtractResult)
        tvExtractResult     = findViewById(R.id.tvExtractResult)

        btnExtract.setOnClickListener { extractNumbers(showToastIfEmpty = true) }
        btnShowNumbers.setOnClickListener { showExtractedNumbers() }
        btnSend.setOnClickListener { startSend() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun extractNumbers(showToastIfEmpty: Boolean): Boolean {
        val text = etLinks.text.toString()
        if (text.isBlank()) {
            if (showToastIfEmpty)
                Toast.makeText(this, "먼저 번호를 붙여넣어주세요", Toast.LENGTH_SHORT).show()
            layoutExtractResult.visibility = View.GONE
            extractedPhones = emptyList()
            return false
        }

        extractedPhones = DataManager.parsePhones(text)

        if (extractedPhones.isEmpty()) {
            layoutExtractResult.visibility = View.GONE
            if (showToastIfEmpty)
                Toast.makeText(this, "번호를 찾지 못했어요\n국가코드 포함 7자리 이상 숫자가 필요합니다", Toast.LENGTH_LONG).show()
            return false
        }

        tvExtractResult.text = "📞 ${extractedPhones.size}개 번호 인식됨"
        layoutExtractResult.visibility = View.VISIBLE
        return true
    }

    private fun showExtractedNumbers() {
        if (extractedPhones.isEmpty()) return
        val list = extractedPhones.joinToString("\n") { "  +$it" }
        AlertDialog.Builder(this)
            .setTitle("인식된 번호 (${extractedPhones.size}개)")
            .setMessage(list)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun startSend() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "먼저 '다른 앱 위에 표시' 권한을 허용해주세요", Toast.LENGTH_LONG).show()
            return
        }
        // 번호 자동 추출 (사용자가 추출 버튼 안 눌렀어도 동작)
        if (extractedPhones.isEmpty()) {
            if (!extractNumbers(showToastIfEmpty = true)) return
        }

        val message = etMessage.text.toString().trim()
        if (message.isBlank()) {
            Toast.makeText(this, "메시지를 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }

        val tempContacts = extractedPhones.mapIndexed { i, phone ->
            Contact(id = System.currentTimeMillis() + i, name = "연락처${i + 1}", phone = phone)
        }
        val tempGroup = Group(
            id = DataManager.ID_MAKEUP,
            name = DataManager.NAME_MAKEUP,
            contacts = tempContacts,
            message = message
        )
        DataManager.saveGroup(this, tempGroup)

        startForegroundService(
            Intent(this, SenderService::class.java)
                .putExtra(SenderService.EXTRA_GROUP_ID, DataManager.ID_MAKEUP)
        )

        Toast.makeText(this, "📤 ${tempContacts.size}명에게 전송 시작!", Toast.LENGTH_SHORT).show()
        finish()
    }
}
