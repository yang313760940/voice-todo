package com.voicetodo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.voicetodo.domain.BackupCounts
import com.voicetodo.domain.BackupPreview
import com.voicetodo.domain.RestoreCopyInfo
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DataBackupScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewShowsVersionDateCountsAndAllBackupActions() {
        var exported = false
        var pickedImport = false
        var confirmed = false
        var cancelled = false
        var previewedCopy = ""
        val preview = BackupPreview(
            formatVersion = 1,
            exportedAt = Instant.parse("2026-11-05T01:00:00Z"),
            appVersion = "0.1.0-test",
            counts = BackupCounts(
                itemCount = 3,
                listCount = 2,
                focusSessionCount = 5,
                recurringSeriesCount = 1,
            ),
        )
        compose.setContent {
            MaterialTheme {
                DataManagementScreen(
                    state = DataBackupUiState(
                        preview = preview,
                        pending = PendingBackupImport("backup.json"),
                        restoreCopies = listOf(
                            RestoreCopyInfo(
                                name = "restore-20261105-090000-000.json",
                                createdAt = Instant.parse("2026-11-05T01:00:00Z"),
                                sizeBytes = 2048,
                            ),
                        ),
                    ),
                    onBack = {},
                    onPickExport = { exported = true },
                    onPickImport = { pickedImport = true },
                    onConfirmImport = { confirmed = true },
                    onCancelImport = { cancelled = true },
                    onPreviewRestoreCopy = { previewedCopy = it },
                )
            }
        }

        compose.onNodeWithTag("data_management_screen").assertIsDisplayed()
        compose.onNodeWithTag("backup_scope").assertIsDisplayed()
        compose.onNodeWithTag("data_management_screen")
            .performScrollToNode(hasText("事项 3 个（其中重复安排 1 个）"))
        compose.onNodeWithText("事项 3 个（其中重复安排 1 个）").assertIsDisplayed()
        compose.onNodeWithText("清单 2 个 · 专注记录 5 条").assertIsDisplayed()
        compose.onNodeWithText("应用版本 0.1.0-test").assertIsDisplayed()
        compose.onNodeWithText("待导入：backup.json").assertIsDisplayed()

        compose.onNodeWithTag("data_management_screen")
            .performScrollToNode(hasTestTag("backup_export"))
        compose.onNodeWithTag("backup_export").performClick()
        assertTrue(exported)
        compose.onNodeWithTag("data_management_screen")
            .performScrollToNode(hasTestTag("backup_import_pick"))
        compose.onNodeWithTag("backup_import_pick").performClick()
        assertTrue(pickedImport)
        compose.onNodeWithTag("data_management_screen")
            .performScrollToNode(hasTestTag("backup_confirm"))
        compose.onNodeWithTag("backup_confirm").performClick()
        assertTrue(confirmed)
        compose.onNodeWithTag("data_management_screen")
            .performScrollToNode(hasTestTag("backup_cancel"))
        compose.onNodeWithTag("backup_cancel").performClick()
        assertTrue(cancelled)
        compose.onNodeWithTag("data_management_screen").performScrollToNode(
            hasTestTag("restore_copy_restore-20261105-090000-000.json"),
        )
        compose.onNodeWithTag("restore_copy_restore-20261105-090000-000.json").performClick()
        assertEquals("restore-20261105-090000-000.json", previewedCopy)
    }

    @Test fun rejectionMessageIsShownWithoutAConfirmAction() {
        compose.setContent {
            MaterialTheme {
                DataManagementScreen(
                    state = DataBackupUiState(error = "无法导入：备份文件不是有效的 JSON"),
                    onBack = {},
                    onPickExport = {},
                    onPickImport = {},
                    onConfirmImport = {},
                    onCancelImport = {},
                    onPreviewRestoreCopy = {},
                )
            }
        }

        compose.onNodeWithTag("data_management_screen")
            .performScrollToNode(hasTestTag("backup_error"))
        compose.onNodeWithTag("backup_error").assertIsDisplayed()
        compose.onAllNodesWithTag("backup_confirm").assertCountEquals(0)
        compose.onNodeWithText("还没有恢复副本。").performScrollTo().assertIsDisplayed()
    }
}
