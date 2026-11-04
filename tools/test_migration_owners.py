import unittest

from migration_owners import problems

CREATE = "create function sadid_private.f() returns int language sql as $$ select 1 $$;\n"
OWN = "alter function sadid_private.f() owner to sadid_auth_reader;\n"
GRANT = "grant create on schema sadid_private to sadid_auth_reader;\n"
REVOKE = "revoke create on schema sadid_private from sadid_auth_reader;\n"


class ProblemsTest(unittest.TestCase):
    def test_a_new_owner_without_create_is_refused(self):
        self.assertIn("needs", problems([("a.sql", CREATE + OWN)])[0])

    def test_a_new_owner_inside_grant_and_revoke_passes(self):
        self.assertEqual(problems([("a.sql", CREATE + GRANT + OWN + REVOKE)]), [])

    def test_a_grant_after_the_owner_change_does_not_count(self):
        self.assertNotEqual(problems([("a.sql", CREATE + OWN + GRANT + REVOKE)]), [])

    def test_a_grant_left_open_is_refused(self):
        self.assertIn("revoke", problems([("a.sql", CREATE + GRANT + OWN)])[0])

    def test_an_internal_owner_in_public_is_refused(self):
        sql = "alter function public.f() owner to sadid_auth_reader;\n"
        self.assertIn("public", problems([("a.sql", sql)])[0])

    def test_postgres_may_own_anything(self):
        self.assertEqual(problems([("a.sql", "alter function public.f() owner to postgres;\n")]), [])

    def test_the_same_owner_again_is_a_no_op(self):
        migrations = [("a.sql", CREATE + GRANT + OWN + REVOKE), ("b.sql", OWN)]
        self.assertEqual(problems(migrations), [])

    def test_a_dropped_function_loses_its_owner(self):
        migrations = [("a.sql", CREATE + GRANT + OWN + REVOKE), ("b.sql", "drop function sadid_private.f();\n" + CREATE + OWN)]
        self.assertEqual(len(problems(migrations)), 1)

    def test_a_comment_is_not_a_statement(self):
        self.assertEqual(problems([("a.sql", "-- " + OWN)]), [])

    def test_an_owner_change_may_span_lines(self):
        sql = "alter function sadid_private.f()\n  owner to sadid_auth_reader;\n"
        self.assertEqual(len(problems([("a.sql", sql)])), 1)


if __name__ == "__main__":
    unittest.main()
