package com.waauto

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * A반 / B반 공통 화면.
 *  - 와츠앱 번호를 통째로 붙여넣어 "연락처1, 연락처2..." 이름으로 저장
 *  - 메시지 입력
 *  - 저장된 번호 전체에 전송
 */
class ClassActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CLASS_ID = "class_id"
        const val EXTRA_CLASS_NAME = "class_name"
    }

    private lateinit var classId: String
    private lateinit var className: String

    private lateinit var tvSavedCount: TextView
    private lateinit var tvSavedPreview: TextView
    private lateinit var etPaste: EditText
    private lateinit var etMessage: EditText
    private lateinit var btnSaveOverwrite: Button
    private lateinit var btnSaveAppend: Button
    private lateinit var btnClear: Button
    private lateinit var btnSend: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_class)

        classId = intent.getStringExtra(EXTRA_CLASS_ID) ?: run { finish(); return }
        className = intent.getStringExtra(EXTRA_CLASS_NAME) ?: classId

        supportActionBar?.title = className
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        tvSavedCount     = findViewById(R.id.tvSavedCount)
        tvSavedPreview   = findViewById(R.id.tvSavedPreview)
        etPaste          = findViewById(R.id.etPaste)
        etMessage        = findViewById(R.id.etMessage)
        btnSaveOverwrite = findViewById(R.id.btnSaveOverwrite)
        btnSaveAppend    = findViewById(R.id.btnSaveAppend)
        btnClear         = findViewById(R.id.btnClear)
        btnSend          = findViewById(R.id.btnSend)

        val group = DataManager.getGroup(this, classId)
        etMessage.setText(group?.message ?: "")
        refreshSaved()

        btnSaveOverwrite.setOnClickListener { save(overwrite = true) }
        btnSaveAppend.setOnClickListener    { save(overwrite = false) }
        btnClear.setOnClickListener         { confirmClear() }
        btnSend.setOnClickListener          { send() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun refreshSaved() {
        val contacts = DataManager.getGroup(this, classId)?.contacts ?: emptyList()
        tvSavedCount.text = "💾 저장된 연락처: ${contacts.size}명"
        tvSavedPreview.text = when {
            contacts.isEmpty() -> "아직 저장된 번호가 없어요.\n아래에 번호를 붙여넣고 저장하세요."
            else -> {
                val preview = contacts.take(8).joinToString("\n") { "  ${it.name}: +${it.phone}" }
                if (contacts.size > 8) "$preview\n  ... 외 ${contacts.size - 8}명" else preview
            }
        }
    }

    private fun save(overwrite: Boolean) {
        val phones = DataManager.parsePhones(etPaste.text.toString())
        if (phones.isEmpty()) {
            Toast.makeText(this, "붙여넣은 텍스트에서 번호를 찾지 못했어요", Toast.LENGTH_SHORT).show()
            return
        }

        val existing = DataManager.getGroup(this, classId)?.contacts ?: emptyList()

        val merged: List<Contact> = if (overwrite) {
            phones.mapIndexed { i, p ->
                Contact(id = System.currentTimeMillis() + i, name = "연락처${i + 1}", phone = p)
            }
        } else {
            // 중복 번호는 추가 안 함, 이름은 통째로 재부여 (연락처1, 2, 3...)
            val existingPhones = existing.map { it.phone }.toSet()
            val toAdd = phones.filter { it !in existingPhones }
            val combined = existing.map { it.phone } + toAdd
            combined.mapIndexed { i, p ->
                Contact(id = System.currentTimeMillis() + i, name = "연락처${i + 1}", phone = p)
            }
        }

        DataManager.saveGroup(
            this,
            Group(
                id = classId,
                name = className,
                contacts = merged,
                message = etMessage.text.toString().trim()
            )
        )

        etPaste.setText("")
        refreshSaved()
        val added = merged.size - if (overwrite) 0 else existing.size
        val msg = if (overwrite) "✅ ${merged.size}명 저장됨 (덮어쓰기)"
                  else "✅ ${added}명 추가됨 (총 ${merged.size}명)"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("전체 삭제")
            .setMessage("${className} 의 저장된 모든 번호를 지울까요?")
            .setPositiveButton("삭제") { _, _ ->
                DataManager.saveGroup(
                    this,
                    Group(
                        id = classId,
                        name = className,
                        contacts = emptyList(),
                        message = etMessage.text.toString().trim()
                    )
                )
                refreshSaved()
                Toast.makeText(this, "🗑️ 비웠어요", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun send() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "먼저 '다른 앱 위에 표시' 권한을 허용해주세요", Toast.LENGTH_LONG).show()
            return
        }
        val msg = etMessage.text.toString().trim()
        if (msg.isBlank()) {
            Toast.makeText(this, "메시지를 입력해주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val current = DataManager.getGroup(this, classId)
            ?: Group(id = classId, name = className)
        val updated = current.copy(message = msg)
        DataManager.saveGroup(this, updated)

        if (updated.contacts.isEmpty()) {
            Toast.makeText(this, "저장된 번호가 없어요. 먼저 번호를 붙여넣고 저장하세요", Toast.LENGTH_LONG).show()
            return
        }

        startForegroundService(
            Intent(this, SenderService::class.java)
                .putExtra(SenderService.EXTRA_GROUP_ID, classId)
        )
        Toast.makeText(this, "📤 ${updated.contacts.size}명에게 전송 시작!", Toast.LENGTH_SHORT).show()
    }
}
