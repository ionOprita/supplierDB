package ro.sellfluence.db.versions;

import ch.claudio.db.DB;

import java.sql.SQLException;

public class SetupDB {
    public static void setupAndUpdateDB(DB db) throws SQLException {
        db.prepareDB(EmagMirrorDBVersion1::version1,
                EmagMirrorDBVersion2::version2,
                EmagMirrorDBVersion3::version3,
                EmagMirrorDBVersion4::version4,
                EmagMirrorDBVersion5::version5,
                EmagMirrorDBVersion6::version6,
                EmagMirrorDBVersion7::version7,
                EmagMirrorDBVersion8::version8,
                EmagMirrorDBVersion9::version9,
                EmagMirrorDBVersion10::version10,
                EmagMirrorDBVersion11::version11,
                EmagMirrorDBVersion12::version12,
                EmagMirrorDBVersion13::version13,
                EmagMirrorDBVersion14::version14,
                EmagMirrorDBVersion15::version15,
                EmagMirrorDBVersion16::version16,
                EmagMirrorDBVersion17::version17,
                EmagMirrorDBVersion18::version18,
                EmagMirrorDBVersion19::version19,
                EmagMirrorDBVersion20::version20,
                EmagMirrorDBVersion21::version21,
                EmagMirrorDBVersion22::version22,
                EmagMirrorDBVersion23::version23,
                EmagMirrorDBVersion24::version24,
                EmagMirrorDBVersion25::version25);
    }
}