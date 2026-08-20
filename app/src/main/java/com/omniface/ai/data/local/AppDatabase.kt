package com.omniface.ai.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.omniface.ai.data.local.dao.AttendanceDao
import com.omniface.ai.data.local.dao.StudentDao
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.StudentEntity

@Database(
    entities = [
        StudentEntity::class,
        FaceTemplateEntity::class,
        AttendanceRecordEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun studentDao(): StudentDao
    abstract fun attendanceDao(): AttendanceDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attendance_records_session_date_student_roll` ON `attendance_records` (`session_date`, `student_roll`)")
            }
        }

        val MIGRATION_1_3 = object : Migration(1, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attendance_records_session_date_student_roll` ON `attendance_records` (`session_date`, `student_roll`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attendance_records_session_date_student_roll` ON `attendance_records` (`session_date`, `student_roll`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `face_templates` ADD COLUMN `quality_score` REAL NOT NULL DEFAULT 100.0")
                db.execSQL("ALTER TABLE `face_templates` ADD COLUMN `sharpness_score` REAL NOT NULL DEFAULT 100.0")
                db.execSQL("ALTER TABLE `face_templates` ADD COLUMN `lighting_score` REAL NOT NULL DEFAULT 100.0")
                db.execSQL("ALTER TABLE `face_templates` ADD COLUMN `consistency_score` REAL NOT NULL DEFAULT 100.0")
            }
        }

        val MIGRATION_1_4 = object : Migration(1, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attendance_records_session_date_student_roll` ON `attendance_records` (`session_date`, `student_roll`)")
                db.execSQL("ALTER TABLE `face_templates` ADD COLUMN `quality_score` REAL NOT NULL DEFAULT 100.0")
                db.execSQL("ALTER TABLE `face_templates` ADD COLUMN `sharpness_score` REAL NOT NULL DEFAULT 100.0")
                db.execSQL("ALTER TABLE `face_templates` ADD COLUMN `lighting_score` REAL NOT NULL DEFAULT 100.0")
                db.execSQL("ALTER TABLE `face_templates` ADD COLUMN `consistency_score` REAL NOT NULL DEFAULT 100.0")
            }
        }

        val MIGRATION_2_4 = object : Migration(2, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attendance_records_session_date_student_roll` ON `attendance_records` (`session_date`, `student_roll`)")
                db.execSQL("ALTER TABLE `face_templates` ADD COLUMN `quality_score` REAL NOT NULL DEFAULT 100.0")
                db.execSQL("ALTER TABLE `face_templates` ADD COLUMN `sharpness_score` REAL NOT NULL DEFAULT 100.0")
                db.execSQL("ALTER TABLE `face_templates` ADD COLUMN `lighting_score` REAL NOT NULL DEFAULT 100.0")
                db.execSQL("ALTER TABLE `face_templates` ADD COLUMN `consistency_score` REAL NOT NULL DEFAULT 100.0")
            }
        }
    }
}
