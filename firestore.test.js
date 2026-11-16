const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastTimestamp(offsetMs = 5000) {
  return new Date(Date.now() - offsetMs);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

// --- 1. Unauthenticated Denials & Global Safety Net ---

test("Unauthenticated user: denied read/list/write across all user collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      createdAt: pastTimestamp(),
      updatedAt: pastTimestamp(),
    })
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("private").doc("profile").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("records").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("medications").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("vitals").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("symptoms").get()
  );
  await assertFails(unauthDb.collection("unknown_collection").doc("x").get());
});

// --- 2. Root User Account (/users/{userId}) ---

test("Owner can create, get, update, delete /users/{userId}; cannot list /users", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const createdTs = pastTimestamp(10000);
  const updatedTs = pastTimestamp(2000);

  const userRef = aliceDb.collection("users").doc(ALICE_UID);
  await assertSucceeds(
    userRef.set({
      userId: ALICE_UID,
      email: "alice@example.com",
      displayName: "Alice",
      createdAt: createdTs,
      updatedAt: updatedTs,
    })
  );
  await assertSucceeds(userRef.get());
  await assertFails(aliceDb.collection("users").get());

  // Valid update preserving userId & createdAt
  await assertSucceeds(
    userRef.update({
      displayName: "Alice Updated",
      updatedAt: pastTimestamp(500),
    })
  );

  // Tampering immutable createdAt fails
  await assertFails(
    userRef.update({
      createdAt: pastTimestamp(100),
      updatedAt: pastTimestamp(100),
    })
  );

  // Ghost field injection fails on create/update
  await assertFails(
    userRef.update({
      role: "admin",
      updatedAt: pastTimestamp(100),
    })
  );

  await assertSucceeds(userRef.delete());
});

test("Cross-user access denied on /users/{userId}", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      createdAt: pastTimestamp(10000),
      updatedAt: pastTimestamp(5000),
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({ displayName: "Hacked", updatedAt: pastTimestamp(100) })
  );
  await assertFails(bobDb.collection("users").doc(ALICE_UID).delete());
});

// --- 3. Private Profile (/users/{userId}/private/profile) ---

test("Owner can create, read, update profile; non-profile docId or invalid data fails", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const createdTs = pastTimestamp(20000);
  const profileRef = aliceDb.collection("users").doc(ALICE_UID).collection("private").doc("profile");

  await assertSucceeds(
    profileRef.set({
      userId: ALICE_UID,
      fullName: "Alice Sharma",
      preferredLanguage: "English",
      bloodGroup: "O+",
      updatedAtTimestamp: 1700000000000,
      createdAt: createdTs,
      updatedAt: pastTimestamp(5000),
    })
  );

  await assertSucceeds(profileRef.get());
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("private").get());

  // Non-profile docId rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("private").doc("other").set({
      userId: ALICE_UID,
      fullName: "Alice Sharma",
      preferredLanguage: "English",
      updatedAtTimestamp: 1700000000000,
      createdAt: createdTs,
      updatedAt: pastTimestamp(5000),
    })
  );

  // Oversized fullName rejected
  await assertFails(
    profileRef.update({
      fullName: "A".repeat(121),
      updatedAt: pastTimestamp(100),
    })
  );

  // Cross-user rejected
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("private").doc("profile").get()
  );
});

// --- 4. Medical Records (/users/{userId}/records/{recordId}) ---

test("Medical records: owner CRUD, valid enum, list, immutable localRecordId & cross-user denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const createdTs = pastTimestamp(20000);
  const recRef = aliceDb.collection("users").doc(ALICE_UID).collection("records").doc("rec_1");

  await assertSucceeds(
    recRef.set({
      userId: ALICE_UID,
      localRecordId: 1,
      title: "CBC Report",
      category: "Lab Report",
      recordDate: 1700000000000,
      createdAtTimestamp: 1700000000000,
      reviewStatus: "UNREVIEWED",
      processedPages: 2,
      updatedAtTimestamp: 1700000000000,
      createdAt: createdTs,
      updatedAt: pastTimestamp(5000),
    })
  );

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("records").get());

  // Valid update
  await assertSucceeds(
    recRef.update({
      reviewStatus: "REVIEWED_AND_SAVED",
      updatedAtTimestamp: 1700000001000,
      updatedAt: pastTimestamp(1000),
    })
  );

  // Invalid reviewStatus rejected
  await assertFails(
    recRef.update({
      reviewStatus: "UNKNOWN_STATUS",
      updatedAt: pastTimestamp(500),
    })
  );

  // Immutable localRecordId rejected
  await assertFails(
    recRef.update({
      localRecordId: 99,
      updatedAt: pastTimestamp(500),
    })
  );

  // Cross-user list & get rejected
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("records").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("records").doc("rec_1").get());
  await assertSucceeds(recRef.delete());
});

// --- 5. Medications (/users/{userId}/medications/{medicationId}) ---

test("Medications: owner CRUD, list, immutable createdAtTimestamp, cross-user denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const medRef = aliceDb.collection("users").doc(ALICE_UID).collection("medications").doc("med_abc-1");

  await assertSucceeds(
    medRef.set({
      userId: ALICE_UID,
      name: "Metformin",
      dosage: "500mg after breakfast",
      timeSlot: "08:30",
      isTaken: false,
      createdAtTimestamp: 1700000000000,
      createdAt: pastTimestamp(15000),
      updatedAt: pastTimestamp(5000),
    })
  );

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("medications").get());
  await assertSucceeds(medRef.update({ isTaken: true, updatedAt: pastTimestamp(500) }));

  // Immutable createdAtTimestamp rejected
  await assertFails(
    medRef.update({ createdAtTimestamp: 1700000999999, updatedAt: pastTimestamp(200) })
  );

  // Cross-user rejected
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("medications").get());
  await assertFails(medRef.withConverter ? bobDb.collection("users").doc(ALICE_UID).collection("medications").doc("med_abc-1").delete() : bobDb.collection("users").doc(ALICE_UID).collection("medications").doc("med_abc-1").delete());
});

// --- 6. Vitals (/users/{userId}/vitals/{vitalId}) ---

test("Vitals: owner CRUD, valid enum, numeric validation, cross-user denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const vitalRef = aliceDb.collection("users").doc(ALICE_UID).collection("vitals").doc("vital_1");

  await assertSucceeds(
    vitalRef.set({
      userId: ALICE_UID,
      localVitalId: 1,
      type: "BLOOD_PRESSURE",
      systolic: 118,
      diastolic: 76.5,
      timestamp: 1700000000000,
      notes: "Morning resting reading",
      createdAt: pastTimestamp(12000),
      updatedAt: pastTimestamp(4000),
    })
  );

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("vitals").get());

  // Invalid type enum rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("vitals").doc("vital_bad").set({
      userId: ALICE_UID,
      localVitalId: 2,
      type: "HEART_RATE",
      timestamp: 1700000000000,
      notes: "",
      createdAt: pastTimestamp(10000),
      updatedAt: pastTimestamp(1000),
    })
  );

  // Cross-user rejected
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("vitals").get());
  await assertSucceeds(vitalRef.delete());
});

// --- 7. Symptoms (/users/{userId}/symptoms/{symptomId}) ---

test("Symptoms: owner CRUD, severity enum & range 1..4, cross-user denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const symRef = aliceDb.collection("users").doc(ALICE_UID).collection("symptoms").doc("sym_1");

  await assertSucceeds(
    symRef.set({
      userId: ALICE_UID,
      localSymptomId: 1,
      symptomName: "Migraine",
      severity: "MODERATE",
      severityScore: 2,
      timestamp: 1700000000000,
      notes: "Mild photophobia",
      triggers: "Screen glare",
      durationMinutes: 45,
      createdAt: pastTimestamp(12000),
      updatedAt: pastTimestamp(4000),
    })
  );

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("symptoms").get());

  // Invalid severityScore rejected
  await assertFails(
    symRef.update({
      severityScore: 5,
      updatedAt: pastTimestamp(500),
    })
  );

  // Cross-user rejected
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("symptoms").get());
  await assertSucceeds(symRef.delete());
});
