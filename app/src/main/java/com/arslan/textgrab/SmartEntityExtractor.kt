package com.arslan.textgrab

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.regex.Pattern

enum class EntityType {
    URL, EMAIL, PHONE, DATE, CURRENCY
}

data class SmartEntity(
    val type: EntityType,
    val value: String,
    val label: String,
    val actionLabel: String
)

object SmartEntityExtractor {

    private val URL_PATTERN = Pattern.compile(
        "\\b(https?://[a-zA-Z0-9.-]+(?:\\.[a-zA-Z]{2,})+(?::\\d+)?(?:/[^\\s]*)?|www\\.[a-zA-Z0-9.-]+(?:\\.[a-zA-Z]{2,})+(?:/[^\\s]*)?)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val EMAIL_PATTERN = Pattern.compile(
        "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"
    )

    private val PHONE_PATTERN = Pattern.compile(
        "\\b(?:\\+?\\d{1,3}[- .]?)?\\(?\\d{2,4}\\)?[- .]?\\d{3,4}[- .]?\\d{3,4}\\b"
    )

    private val DATE_PATTERN = Pattern.compile(
        "\\b(?:\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2}(?:st|nd|rd|th)?,?\\s+\\d{4})\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val CURRENCY_PATTERN = Pattern.compile(
        "(?:[\\$\\€\\£\\¥\\₹]\\s?\\d+(?:[.,]\\d{2})?|\\b\\d+(?:[.,]\\d{2})?\\s?(?:USD|EUR|GBP|JPY|INR)\\b)",
        Pattern.CASE_INSENSITIVE
    )

    fun extract(text: String): List<SmartEntity> {
        if (text.isBlank()) return emptyList()
        val results = mutableListOf<SmartEntity>()
        val seen = mutableSetOf<String>()

        // URLs
        val urlMatcher = URL_PATTERN.matcher(text)
        while (urlMatcher.find()) {
            val raw = urlMatcher.group().trim()
            val clean = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
            if (seen.add(clean)) {
                results.add(
                    SmartEntity(
                        type = EntityType.URL,
                        value = clean,
                        label = "Website Link",
                        actionLabel = "Open Link"
                    )
                )
            }
        }

        // Emails
        val emailMatcher = EMAIL_PATTERN.matcher(text)
        while (emailMatcher.find()) {
            val email = emailMatcher.group().trim()
            if (seen.add(email)) {
                results.add(
                    SmartEntity(
                        type = EntityType.EMAIL,
                        value = email,
                        label = "Email Address",
                        actionLabel = "Send Email"
                    )
                )
            }
        }

        // Phone numbers
        val phoneMatcher = PHONE_PATTERN.matcher(text)
        while (phoneMatcher.find()) {
            val phone = phoneMatcher.group().trim()
            // Avoid false positives like short numbers
            val digitsOnly = phone.filter { it.isDigit() }
            if (digitsOnly.length in 7..15 && seen.add(phone)) {
                results.add(
                    SmartEntity(
                        type = EntityType.PHONE,
                        value = phone,
                        label = "Phone Number",
                        actionLabel = "Call Number"
                    )
                )
            }
        }

        // Dates
        val dateMatcher = DATE_PATTERN.matcher(text)
        while (dateMatcher.find()) {
            val date = dateMatcher.group().trim()
            if (seen.add(date)) {
                results.add(
                    SmartEntity(
                        type = EntityType.DATE,
                        value = date,
                        label = "Date & Calendar",
                        actionLabel = "Add to Calendar"
                    )
                )
            }
        }

        // Currency
        val currMatcher = CURRENCY_PATTERN.matcher(text)
        while (currMatcher.find()) {
            val curr = currMatcher.group().trim()
            if (seen.add(curr)) {
                results.add(
                    SmartEntity(
                        type = EntityType.CURRENCY,
                        value = curr,
                        label = "Price / Amount",
                        actionLabel = "Copy Amount"
                    )
                )
            }
        }

        return results
    }

    fun executeAction(context: Context, entity: SmartEntity) {
        when (entity.type) {
            EntityType.URL -> {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(entity.value))
                    context.startActivity(intent)
                }
            }
            EntityType.EMAIL -> {
                runCatching {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:${entity.value}")
                    }
                    context.startActivity(intent)
                }
            }
            EntityType.PHONE -> {
                runCatching {
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:${entity.value.filter { it.isDigit() || it == '+' }}")
                    }
                    context.startActivity(intent)
                }
            }
            EntityType.DATE -> {
                runCatching {
                    val intent = Intent(Intent.ACTION_INSERT).apply {
                        data = Uri.parse("content://com.android.calendar/events")
                        putExtra("title", "Event from TextGrab")
                    }
                    context.startActivity(intent)
                }
            }
            EntityType.CURRENCY -> {
                // Clipboard copy handled in UI
            }
        }
    }
}
