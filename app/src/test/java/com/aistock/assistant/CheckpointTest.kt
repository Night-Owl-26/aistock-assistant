package com.aistock.assistant

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistock.assistant.database.CheckpointDb
import com.aistock.assistant.database.CheckpointEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class CheckpointTest {
    @Test
    fun roomWorks() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CheckpointDb::class.java)
            .allowMainThreadQueries().build()
        db.dao().insert(CheckpointEntity("1", "a"))
        assertEquals(1, db.dao().count())
        db.close()
    }
}
