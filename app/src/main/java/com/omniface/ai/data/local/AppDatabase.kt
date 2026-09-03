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
    version = 6,
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

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Face templates: unique (student_roll, angle_type) constraint
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_face_templates_student_roll_angle_type` ON `face_templates` (`student_roll`, `angle_type`)")
                // Attendance: unique (session_date, student_roll) + missing timestamp index
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_attendance_records_session_date_student_roll` ON `attendance_records` (`session_date`, `student_roll`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attendance_records_timestamp` ON `attendance_records` (`timestamp`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Migrate attendance_records to have ON DELETE CASCADE on student_roll foreign key
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `attendance_records_new` (
                        `record_id` TEXT NOT NULL,
                        `student_roll` TEXT NOT NULL,
                        `student_name` TEXT NOT NULL,
                        `session_date` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `confidence_pct` REAL NOT NULL,
                        `security_tier` TEXT NOT NULL,
                        `sha256_hash` TEXT NOT NULL,
                        `is_synced` INTEGER NOT NULL,
                        PRIMARY KEY(`record_id`),
                        FOREIGN KEY(`student_roll`) REFERENCES `students`(`roll_number`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `attendance_records_new` (`record_id`, `student_roll`, `student_name`, `session_date`, `timestamp`, `confidence_pct`, `security_tier`, `sha256_hash`, `is_synced`)
                    SELECT `record_id`, `student_roll`, `student_name`, `session_date`, `timestamp`, `confidence_pct`, `security_tier`, `sha256_hash`, `is_synced`
                    FROM `attendance_records`
                """.trimIndent())
                db.execSQL("DROP TABLE `attendance_records`")
                db.execSQL("ALTER TABLE `attendance_records_new` RENAME TO `attendance_records`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attendance_records_session_date_timestamp` ON `attendance_records` (`session_date`, `timestamp`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_attendance_records_session_date_student_roll` ON `attendance_records` (`session_date`, `student_roll`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attendance_records_student_roll` ON `attendance_records` (`student_roll`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attendance_records_is_synced` ON `attendance_records` (`is_synced`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attendance_records_timestamp` ON `attendance_records` (`timestamp`)")
            }
        }
    }
}
