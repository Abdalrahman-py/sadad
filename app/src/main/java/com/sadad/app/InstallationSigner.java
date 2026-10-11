package com.sadad.app;

import org.json.JSONObject;

/**
 * The device identity every signed request is built from: the installation id, the public key the
 * server already knows, and the signature over the canonical text in
 * {@code sadid-ledger/proof.ts}. Split from its Keystore implementation so the signing contract can
 * be exercised in the JVM, where a real P-256 key is cheap to generate.
 */
interface InstallationSigner {
    String id();

    JSONObject publicKey() throws Exception;

    /** Signature over {@code sadid-v3\nMETHOD\nroute\nSHA-256(raw)\ntime\nnonce\ninstallation}. */
    String sign(String method, String route, String raw, String time, String nonce) throws Exception;
}
