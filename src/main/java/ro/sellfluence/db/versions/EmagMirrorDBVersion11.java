package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;
import static ro.sellfluence.support.Time.timeE;

class EmagMirrorDBVersion11 {

    /**
     * Add and populate the account column in the vendor table.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to caller.
     */
    static void version11(Connection db) throws SQLException {
        timeE("Add surrogate ID", () -> addSurrogateId(db));
        timeE("Populate surrogate ID", () -> populateSurrogateId(db));
        timeE("Add new reference to dependent tables", () -> addNewReferenceToDependentTables(db));
        timeE("Populate new reference to dependent tables", () -> populateNewReferenceInDependentTables(db));
        timeE("Drop old foreign constraint", () -> dropOldForeignKeyConstraint(db));
        timeE("Drop the old primary key", () -> dropOldPrimaryKey(db));
        timeE("Make surrogate ID the new primary key", () -> makeSurrogatePrimaryKey(db));
        timeE("Add foreign constraints to the new primary key", () -> addNewForeignKeyConstraint(db));
        timeE("Drop order and vendor ID from dependent tables", () -> dropOrderIdVendorIdFromDependentTables(db));
    }


    private static void populateSurrogateId(Connection db) throws SQLException {
        executeStatement(db, """
                UPDATE public.emag_order
                SET surrogate_id = nextval('emag_order_surrogate_id_seq')
                WHERE surrogate_id IS NULL;
                """);
    }

    private static void dropOrderIdVendorIdFromDependentTables(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE public.attachment DROP COLUMN order_id, DROP COLUMN vendor_id;
                """);
        executeStatement(db, """
                ALTER TABLE public.enforced_vendor_courier_account DROP COLUMN order_id, DROP COLUMN vendor_id;
                """);
        executeStatement(db, """
                ALTER TABLE public.flag DROP COLUMN order_id, DROP COLUMN vendor_id;
                """);
        executeStatement(db, """
                ALTER TABLE public.product_in_order DROP COLUMN order_id, DROP COLUMN vendor_id;
                """);
        executeStatement(db, """
                ALTER TABLE public.voucher DROP COLUMN order_id, DROP COLUMN vendor_id;
                """);
        executeStatement(db, """
                ALTER TABLE public.voucher_split DROP COLUMN order_id, DROP COLUMN vendor_id;
                """);
    }

    private static void dropOldForeignKeyConstraint(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE public.attachment DROP CONSTRAINT attachment_order_id_vendor_id_fkey;
                """);
        executeStatement(db, """
                ALTER TABLE public.enforced_vendor_courier_account DROP CONSTRAINT enforced_vendor_courier_account_order_id_vendor_id_fkey;
                """);
        try {
            executeStatement(db, """
                    DO $$
                         BEGIN
                             IF EXISTS (
                                 SELECT 1
                                 FROM information_schema.table_constraints
                                 WHERE constraint_name = 'flag_order_id_vendor_id_fkey'
                                   AND table_name = 'flag'
                                   AND constraint_schema = 'public'
                             ) THEN
                                 EXECUTE 'ALTER TABLE public.flag DROP CONSTRAINT flag_order_id_vendor_id_fkey';
                             END IF;
                         END;
                         $$;
                    """);
        } catch (SQLException e) {
            System.out.println("Ignoring missing constraint.");
        }
        executeStatement(db, """
                ALTER TABLE public.product_in_order DROP CONSTRAINT product_in_order_order_id_vendor_id_fkey;
                """);
        executeStatement(db, """
                ALTER TABLE public.voucher DROP CONSTRAINT voucher_order_id_vendor_id_fkey;
                """);
        executeStatement(db, """
                ALTER TABLE public.voucher_split DROP CONSTRAINT voucher_split_order_id_vendor_id_fkey;
                """);
    }

    private static void addNewForeignKeyConstraint(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE public.attachment
                    ADD CONSTRAINT attachment_emag_order_surrogate_id_fkey
                    FOREIGN KEY (emag_order_surrogate_id) REFERENCES public.emag_order(surrogate_id);
                """);
        executeStatement(db, """
                ALTER TABLE public.enforced_vendor_courier_account
                    ADD CONSTRAINT enforced_vendor_courier_account_emag_order_surrogate_id_fkey
                    FOREIGN KEY (emag_order_surrogate_id) REFERENCES public.emag_order(surrogate_id);
                """);
        executeStatement(db, """
                ALTER TABLE public.flag
                    ADD CONSTRAINT flag_emag_order_surrogate_id_fkey
                    FOREIGN KEY (emag_order_surrogate_id) REFERENCES public.emag_order(surrogate_id);
                """);
        executeStatement(db, """
                ALTER TABLE public.product_in_order
                    ADD CONSTRAINT product_in_order_emag_order_surrogate_id_fkey
                    FOREIGN KEY (emag_order_surrogate_id) REFERENCES public.emag_order(surrogate_id);
                """);
        executeStatement(db, """
                ALTER TABLE public.voucher
                    ADD CONSTRAINT voucher_emag_order_surrogate_id_fkey
                    FOREIGN KEY (emag_order_surrogate_id) REFERENCES public.emag_order(surrogate_id);
                """);
        executeStatement(db, """
                ALTER TABLE public.voucher_split
                    ADD CONSTRAINT voucher_split_emag_order_surrogate_id_fkey
                    FOREIGN KEY (emag_order_surrogate_id) REFERENCES public.emag_order(surrogate_id);
                """);
    }

    private static void populateNewReferenceInDependentTables(Connection db) throws SQLException {
        executeStatement(db, """
                UPDATE public.attachment a
                SET emag_order_surrogate_id = eo.surrogate_id
                FROM public.emag_order eo
                WHERE a.order_id = eo.id AND a.vendor_id = eo.vendor_id;
                """);
        executeStatement(db, """
                UPDATE public.enforced_vendor_courier_account ec
                SET emag_order_surrogate_id = eo.surrogate_id
                FROM public.emag_order eo
                WHERE ec.order_id = eo.id AND ec.vendor_id = eo.vendor_id;
                """);
        executeStatement(db, """
                UPDATE public.flag f
                SET emag_order_surrogate_id = eo.surrogate_id
                FROM public.emag_order eo
                WHERE f.order_id = eo.id AND f.vendor_id = eo.vendor_id;
                """);
        executeStatement(db, """
                UPDATE public.product_in_order pio
                SET emag_order_surrogate_id = eo.surrogate_id
                FROM public.emag_order eo
                WHERE pio.order_id = eo.id AND pio.vendor_id = eo.vendor_id;
                """);
        executeStatement(db, """
                UPDATE public.voucher v
                SET emag_order_surrogate_id = eo.surrogate_id
                FROM public.emag_order eo
                WHERE v.order_id = eo.id AND v.vendor_id = eo.vendor_id;
                """);
        executeStatement(db, """
                UPDATE public.voucher_split vs
                SET emag_order_surrogate_id = eo.surrogate_id
                FROM public.emag_order eo
                WHERE vs.order_id = eo.id AND vs.vendor_id = eo.vendor_id;
                """);
    }

    private static void addNewReferenceToDependentTables(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE public.attachment ADD COLUMN emag_order_surrogate_id INTEGER;
                """);
        executeStatement(db, """
                ALTER TABLE public.enforced_vendor_courier_account ADD COLUMN emag_order_surrogate_id INTEGER;
                """);
        executeStatement(db, """
                ALTER TABLE public.flag ADD COLUMN emag_order_surrogate_id INTEGER;
                """);
        executeStatement(db, """
                ALTER TABLE public.product_in_order ADD COLUMN emag_order_surrogate_id INTEGER;
                """);
        executeStatement(db, """
                ALTER TABLE public.voucher ADD COLUMN emag_order_surrogate_id INTEGER;
                """);
        executeStatement(db, """
                ALTER TABLE public.voucher_split ADD COLUMN emag_order_surrogate_id INTEGER;
                """);
    }

    private static void addSurrogateId(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE public.emag_order ADD COLUMN surrogate_id SERIAL;
                """);
    }

    private static void makeSurrogatePrimaryKey(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE public.emag_order ADD PRIMARY KEY (surrogate_id);
                """);
    }

    private static void dropOldPrimaryKey(Connection db) throws SQLException {
        executeStatement(db, """
                ALTER TABLE public.emag_order DROP CONSTRAINT emag_order_pkey;
                """);
    }
}