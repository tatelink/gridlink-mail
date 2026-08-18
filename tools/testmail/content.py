"""The mail corpus: what actually gets written into the mailbox.

Hand-authored threads carry the conversations a person would recognise, and the
templated bulk underneath them carries the volume. Real mailboxes are the same shape:
a few dozen conversations you would miss if they vanished, sitting on top of a few
hundred receipts and notifications you would not.

Nothing here talks to a server. Everything is data plus tiny builders, so the corpus can
be inspected, diffed and reviewed without a mailbox anywhere near it.
"""

from __future__ import annotations

# Attachment specs are tuples so they stay readable inline in the thread data:
#   ("pdf", filename, title, [lines])
#   ("chart", filename, title, [bar values])
#   ("csv", filename, [header], [[row], ...])
#   ("photo", filename, initials)

# ---------------------------------------------------------------------------------
# Conversations. "me" is Avery: those turns land in Sent Items, the rest in `folder`.
# gap is minutes since the previous turn in the same thread.
# ---------------------------------------------------------------------------------

THREADS: list[dict] = [
    {
        "subject": "Q3 platform review, draft deck",
        "folder": "INBOX",
        "with": "paloma",
        "cc": ["nadia"],
        "days_ago": 2,
        "turns": [
            (
                "paloma",
                0,
                "Avery, first pass at the Q3 deck is attached. I have left slides 6 and 7 "
                "empty because those are the ingest numbers and I would rather have your "
                "figures than my guesses.\n\n"
                "Review is Thursday at 10. If you can get me anything by Wednesday lunchtime "
                "I can fold it in before I send it up.\n\nPaloma",
                ("pdf", "q3-platform-review-draft.pdf", "Q3 Platform Review (draft)",
                 ["1. Where we said we would be in April",
                  "2. Where we actually are",
                  "3. Ingest volume and cost",
                  "4. Reliability: the two incidents",
                  "5. What we are asking for in Q4",
                  "6. [ingest numbers - Avery]",
                  "7. [cost per million events - Avery]"]),
            ),
            (
                "me",
                190,
                "Got it. Slide 6 is easy, the numbers are already in the weekly export.\n\n"
                "Slide 7 I want to be careful with. Cost per million events dropped 19% but "
                "about half of that is the reserved capacity deal Malcolm signed, not anything "
                "we did to the pipeline. If we present the whole 19% as an engineering win it "
                "will be quoted back at us next quarter when there is no second discount to "
                "find. I would rather split the line.\n\nAvery",
                None,
            ),
            (
                "paloma",
                55,
                "Agreed, split it. Two bars, label the second one clearly as commercial.\n\n"
                "That is the kind of thing that saves us a bad conversation in January.",
                None,
            ),
            (
                "me",
                1450,
                "Both slides attached as a chart plus the underlying numbers. The engineering "
                "half of the improvement is 9.4%, the commercial half is 9.6%.\n\nAvery",
                ("chart", "cost-per-million-q3.png", "Cost per million events",
                 [100, 94, 88, 81]),
            ),
            (
                "paloma",
                300,
                "Perfect. Deck is locked, see you Thursday.",
                None,
            ),
        ],
    },
    {
        "subject": "Ingest lag on the EU shard",
        "folder": "INBOX",
        "with": "dmitri",
        "cc": ["tomas"],
        "days_ago": 4,
        "turns": [
            (
                "dmitri",
                0,
                "EU shard is running about 40 minutes behind since roughly 02:00 UTC. Not "
                "paging anyone yet because nothing is dropping, it is just late.\n\n"
                "My first suspicion is the retention job overlapping the backfill window "
                "again. Same shape as March.\n\nDmitri",
                None,
            ),
            (
                "tomas",
                35,
                "Confirming from the SRE side: no errors, queue depth is climbing steadily "
                "rather than spiking. That does look like contention, not a fault.",
                None,
            ),
            (
                "me",
                80,
                "If it is the retention job then the fix from March was supposed to be the "
                "window move, and I want to know whether that actually shipped or whether it "
                "got reverted in the June rollback. Can one of you check the deploy history "
                "before we change anything? I would rather not fix it twice.",
                None,
            ),
            (
                "dmitri",
                140,
                "You were right to ask. It shipped, then the June rollback took it with it. "
                "Nobody noticed because the EU volume was low all summer.\n\n"
                "Re-applying now, with a test this time so it cannot quietly go away again.",
                None,
            ),
            (
                "me",
                40,
                "Thank you. Please put the test in the same MR as the fix.",
                None,
            ),
            (
                "dmitri",
                900,
                "Lag is back under two minutes. MR is up, test included.",
                None,
            ),
        ],
    },
    {
        "subject": "Thursday climbing",
        "folder": "INBOX",
        "with": "jules",
        "days_ago": 1,
        "turns": [
            (
                "jules",
                0,
                "Are we on for Thursday? I have not been since the wall reset and I hear the "
                "blue route is much harder now.",
                None,
            ),
            ("me", 45, "On. 6:30, usual spot. I will bring the good chalk.", None),
            (
                "jules",
                20,
                "The good chalk is the only reason I keep you around.",
                None,
            ),
        ],
    },
    {
        "subject": "Noor's birthday, the weekend of the 12th",
        "folder": "INBOX",
        "with": "mei",
        "cc": ["mom"],
        "days_ago": 6,
        "turns": [
            (
                "mei",
                0,
                "Noor turns seven on the 12th and she has asked, unprompted, for you to be "
                "there. I am not going to pretend that is not emotional blackmail but it is "
                "very effective emotional blackmail.\n\n"
                "Saturday afternoon, our place. Hugo is doing the cake and refuses all help.",
                None,
            ),
            (
                "me",
                200,
                "I will be there. Flying in Friday night, out Sunday evening.\n\n"
                "What does a seven year old want that is not a screen? I have been out of "
                "the loop for a year and I do not want to be the aunt who gets it wrong.",
                None,
            ),
            (
                "mei",
                90,
                "Anything to do with rocks. She has a shoebox of them under the bed and she "
                "has named several. A proper little geology kit would probably end me.",
                None,
            ),
            (
                "mom",
                240,
                "I am bringing the noodles. Do not both bring noodles again.",
                None,
            ),
        ],
    },
    {
        "subject": "Access review, Q3, action required",
        "folder": "INBOX",
        "with": "olu",
        "days_ago": 9,
        "turns": [
            (
                "olu",
                0,
                "Quarterly access review is open. You hold 14 entitlements, 3 of which have "
                "not been used in 90 days. The list is attached.\n\n"
                "Please either justify or drop each of the three by the 25th. If nothing "
                "comes back they are dropped automatically, which is usually the right "
                "outcome anyway.\n\nOluwaseun",
                ("csv", "access-review-avery.csv",
                 ["entitlement", "granted", "last_used", "status"],
                 [["prod-ingest-read", "2024-11-02", "2026-08-16", "active"],
                  ["prod-ingest-write", "2025-03-14", "2026-08-11", "active"],
                  ["billing-export", "2025-01-20", "2026-04-02", "stale"],
                  ["legacy-etl-admin", "2024-06-08", "2025-12-19", "stale"],
                  ["vendor-portal-kestrel", "2025-09-01", "2026-05-30", "stale"]]),
            ),
            (
                "me",
                420,
                "Drop legacy-etl-admin and vendor-portal-kestrel, neither is needed.\n\n"
                "Keep billing-export. It is only used at quarter close, which is why it looks "
                "stale in the middle of a quarter, and losing it in week 13 would be its own "
                "small emergency.",
                None,
            ),
            (
                "olu",
                180,
                "Reasonable. Billing-export is marked as periodic so it will stop flagging.",
                None,
            ),
        ],
    },
    {
        "subject": "Kestrel statement of work, redlines",
        "folder": "INBOX",
        "with": "grace",
        "cc": ["raf"],
        "days_ago": 12,
        "turns": [
            (
                "grace",
                0,
                "Redlines attached. Two things I will not sign off on as drafted:\n\n"
                "1. Clause 7.2 gives them the right to subcontract without notice. Notice is "
                "not a big ask and we always get it.\n"
                "2. The data processing addendum references a schedule that is not attached.\n\n"
                "Everything else is standard.\n\nGrace",
                ("pdf", "kestrel-sow-redlines.pdf", "Kestrel SOW - redlines",
                 ["Clause 7.2 - subcontracting: NOTICE REQUIRED",
                  "Clause 9.1 - term: accepted",
                  "Clause 11 - liability cap: accepted at 12 months fees",
                  "DPA Schedule 2: MISSING, please supply"]),
            ),
            (
                "raf",
                600,
                "Schedule 2 was an oversight on our side, apologies. Attaching it now.\n\n"
                "Notice on subcontracting is fine, we would never use it without telling you.",
                None,
            ),
            (
                "me",
                300,
                "If you would never use it without telling us, then writing that down costs "
                "you nothing. Let us take the notice clause and close this out.",
                None,
            ),
            (
                "grace",
                220,
                "Countersigned copy will be in your inbox tomorrow.",
                None,
            ),
        ],
    },
    {
        "subject": "Nutmeg's booster and the limp",
        "folder": "INBOX",
        "with": "vet",
        "days_ago": 16,
        "turns": [
            (
                "vet",
                0,
                "Hello Avery,\n\nNutmeg is due her rabies booster this month. We have a slot "
                "Tuesday the 25th at 4:15pm, or Thursday the 27th at 9:00am.\n\n"
                "You mentioned a limp on the last visit. If it is still there please say so "
                "when you book and we will allow extra time.\n\nBrookvale Veterinary",
                None,
            ),
            (
                "me",
                1100,
                "Tuesday the 25th at 4:15 please. The limp comes and goes, mostly after long "
                "walks, and it is the back left. Extra time would be good.",
                None,
            ),
            (
                "vet",
                240,
                "Booked. See you both on the 25th.",
                None,
            ),
        ],
    },
    {
        "subject": "Book club: we are all pretending to have finished it",
        "folder": "INBOX",
        "with": "iris",
        "cc": ["theo", "lena"],
        "days_ago": 8,
        "turns": [
            (
                "iris",
                0,
                "Honest show of hands before Sunday. Who has actually finished it?\n\n"
                "I am at page 300 of 480 and I have started resenting the author personally.",
                None,
            ),
            ("theo", 65, "Page 112. I am not going to make it. I will bring wine instead.", None),
            (
                "lena",
                130,
                "Finished it. It gets much better after 350, which is a terrible thing to have "
                "to say about a book.",
                None,
            ),
            (
                "me",
                200,
                "Finished it last week and I am with Lena. The last hundred pages are doing "
                "work the first three hundred should have done.\n\n"
                "Proposal: next one is under 300 pages, no exceptions, and I get to pick.",
                None,
            ),
            ("iris", 40, "Seconded, enthusiastically.", None),
        ],
    },
    {
        "subject": "Guest lecture, week of the 6th",
        "folder": "Archive",
        "with": "prof",
        "days_ago": 34,
        "turns": [
            (
                "prof",
                0,
                "Avery, would you be willing to give the applied data seminar again this term? "
                "Same format as last year, 50 minutes plus questions, roughly 40 students.\n\n"
                "The week of the 6th is the one that works on my side.\n\nAurelio",
                None,
            ),
            (
                "me",
                1500,
                "Yes, gladly. The week of the 6th works.\n\n"
                "I would like to change the second half though. Last year I spent it on tooling "
                "and the questions afterwards were all about how you decide what is worth "
                "measuring in the first place, which is the more useful thing and I skipped it.",
                None,
            ),
            (
                "prof",
                2600,
                "That is exactly the half they need. Room 214, 2pm, I will send the parking pass "
                "nearer the time.",
                None,
            ),
        ],
    },
    {
        "subject": "Roof leak in the back bedroom",
        "folder": "Archive",
        "with": "landlord",
        "days_ago": 41,
        "turns": [
            (
                "me",
                0,
                "Hello Ruth,\n\nThere is water coming in at the back bedroom ceiling, at the "
                "corner nearest the chimney. It started with Saturday's storm and it is worse "
                "today. Photo attached.\n\n"
                "It is not an emergency yet but it will be if it is left.\n\nAvery",
                ("chart", "ceiling-damp-readings.png", "Damp meter, back bedroom", [12, 19, 31, 44]),
            ),
            (
                "landlord",
                420,
                "Thank you for telling me straight away rather than in three weeks. Roofer is "
                "booked for Wednesday morning between 8 and 12.",
                None,
            ),
            (
                "landlord",
                4300,
                "Roofer has been. Flashing at the chimney had failed, it is replaced and the "
                "ceiling will need repainting once it dries out. That is on me, not you.",
                None,
            ),
            ("me", 200, "Appreciated. I will let it dry and let you know when it is ready.", None),
        ],
    },
    {
        "subject": "Are you around in October?",
        "folder": "INBOX",
        "with": "darius",
        "days_ago": 5,
        "turns": [
            (
                "darius",
                0,
                "We are doing the cabin the second weekend of October. Same one as two years "
                "ago, the one with the terrible mattress and the perfect porch.\n\n"
                "Six of us so far. Say yes.",
                None,
            ),
            (
                "me",
                700,
                "Yes, provisionally. I have a work thing that might land the same weekend and I "
                "will know by the end of the month. Do not hold a bed for me past then.",
                None,
            ),
            (
                "darius",
                160,
                "Provisional yes accepted. I will hold it until the 30th and then give it to "
                "Kwame, who has been asking.",
                None,
            ),
        ],
    },
    {
        "subject": "Design review: the empty states",
        "folder": "INBOX",
        "with": "hana",
        "cc": ["nadia"],
        "days_ago": 3,
        "turns": [
            (
                "hana",
                0,
                "I have redrawn the four empty states. The one I want your eye on is the "
                "no-results state, because it is the only one where the user has done "
                "something and got nothing back, and the current copy blames them for it.\n\n"
                "Sketches attached.",
                ("pdf", "empty-states-v3.pdf", "Empty states, v3",
                 ["1. No data yet (new workspace)",
                  "2. No results (query returned nothing)",
                  "3. Permission denied",
                  "4. Offline"]),
            ),
            (
                "me",
                260,
                "State 2 is the right thing to worry about. The copy currently says 'Try a "
                "different search', which reads as 'you searched wrong'.\n\n"
                "The honest version is that we do not have data for that range yet. Say that. "
                "The user cannot fix a gap in our coverage by typing better.",
                None,
            ),
            (
                "hana",
                90,
                "Yes. Rewriting it as a statement about our data rather than their query.",
                None,
            ),
        ],
    },
    {
        "subject": "Following up re: Staff Data Engineer role",
        "folder": "Archive",
        "with": "recruiter",
        "days_ago": 27,
        "turns": [
            (
                "recruiter",
                0,
                "Hi Avery,\n\nI came across your profile and was immediately impressed. I am "
                "working with a well funded Series C in the data space and I think you would be "
                "a phenomenal fit for their Staff Data Engineer role.\n\n"
                "Would you have 15 minutes this week for a quick chat?\n\nBest,\nGideon",
                None,
            ),
            (
                "recruiter",
                7300,
                "Hi Avery, just floating this back to the top of your inbox in case it got "
                "buried. Still keen to connect.\n\nGideon",
                None,
            ),
            (
                "me",
                900,
                "Gideon, thank you, but I am not looking at the moment and I would rather not "
                "be contacted about this role again. If something changes I will reach out.",
                None,
            ),
        ],
    },
    {
        "subject": "Tax documents for the filing",
        "folder": "Archive",
        "with": "accountant",
        "days_ago": 52,
        "turns": [
            (
                "accountant",
                0,
                "Avery, to finish the return I still need:\n\n"
                "- the 1099 from the consulting work in February\n"
                "- the closing statement from the refinance\n"
                "- confirmation of the charitable contributions total\n\n"
                "Nothing else outstanding.\n\nDesmond",
                None,
            ),
            (
                "me",
                2000,
                "1099 and closing statement attached. Charitable total for the year is $2,140, "
                "which is three receipts, all in the folder I shared with you in March.",
                ("pdf", "1099-consulting-feb.pdf", "Form 1099-NEC (copy)",
                 ["Payer: Kestrel Consulting", "Nonemployee compensation: 8,400.00",
                  "Tax year: 2025"]),
            ),
            (
                "accountant",
                3000,
                "That is everything. Return is filed, confirmation attached. Refund should land "
                "in nine to twelve days.",
                ("pdf", "filing-confirmation.pdf", "Filing confirmation",
                 ["Status: ACCEPTED", "Submitted: 2026-06-29", "Estimated refund: 1,180.00"]),
            ),
        ],
    },
    {
        "subject": "Interview panel for the ingest role",
        "folder": "INBOX",
        "with": "ellen",
        "cc": ["sam"],
        "days_ago": 7,
        "turns": [
            (
                "ellen",
                0,
                "Avery, can you take the systems design interview for the ingest opening? Two "
                "candidates so far, a third likely next week.\n\n"
                "The slots would be Tuesday 11:00 and Wednesday 15:00.",
                None,
            ),
            (
                "me",
                380,
                "Tuesday 11:00 yes. Wednesday 15:00 clashes with the platform review.\n\n"
                "One request: send me the scorecard before the interview and not after. Last "
                "round I found out what I was supposed to be assessing once it was over.",
                None,
            ),
            (
                "ellen",
                150,
                "Fair, and noted for the whole panel. Scorecard is attached and I will send it "
                "up front from now on.",
                ("pdf", "systems-design-scorecard.pdf", "Systems design scorecard",
                 ["Signal 1: decomposes an ambiguous problem",
                  "Signal 2: reasons about failure before scale",
                  "Signal 3: states assumptions out loud",
                  "Signal 4: changes their mind when given new information"]),
            ),
        ],
    },
    {
        "subject": "Car: the noise is back",
        "folder": "Archive",
        "with": "mechanic",
        "days_ago": 22,
        "turns": [
            (
                "me",
                0,
                "The rattle from June is back, same conditions: only under braking, only when "
                "cold, gone after ten minutes. You replaced the front pads last time and it was "
                "quiet for about six weeks.",
                None,
            ),
            (
                "mechanic",
                500,
                "Bring it in Thursday. If it is quiet again after six weeks then the pads were "
                "not the cause, they just changed the noise for a while. I want to look at the "
                "anti-rattle clips and the caliper slides.\n\nNo charge for the look.",
                None,
            ),
            (
                "mechanic",
                6000,
                "Caliper slide pins on the near side were dry and one clip was missing. Cleaned, "
                "greased, clip replaced. Invoice attached, and I have knocked the diagnostic off "
                "since we should have caught it in June.",
                ("pdf", "invoice-4417.pdf", "Camden Auto - invoice 4417",
                 ["Anti-rattle clip (1)  ................  14.00",
                  "Slide pin service  ..................  46.00",
                  "Diagnostic  .........................  0.00 (waived)",
                  "Total  ..............................  60.00"]),
            ),
        ],
    },
    {
        "subject": "Photos from the ridge walk",
        "folder": "INBOX",
        "with": "photog",
        "days_ago": 11,
        "turns": [
            (
                "photog",
                0,
                "Finally got through the ridge walk set. There are 240 usable frames and I have "
                "picked 18. The light in the last hour did all the work and I will not pretend "
                "otherwise.\n\nContact sheet attached, full set in the shared folder.",
                ("chart", "contact-sheet-summary.png", "Frames per hour", [40, 62, 55, 83]),
            ),
            (
                "me",
                600,
                "These are lovely. Number 11 is the one, the one where Corin is half out of "
                "frame and clearly mid-sentence. Can I have that one large?",
                None,
            ),
            ("photog", 800, "Sending a print file. It will be about 40MB, watch your inbox.", None),
        ],
    },
    {
        "subject": "Reserved capacity renewal",
        "folder": "INBOX",
        "with": "malcolm",
        "cc": ["paloma"],
        "days_ago": 10,
        "turns": [
            (
                "malcolm",
                0,
                "The reserved capacity deal renews on the 30th. Same terms available if we "
                "commit for 12 months, better rate at 24.\n\n"
                "I need a number from you: what is the realistic floor of our usage over the "
                "next two years? Not the forecast, the floor.",
                None,
            ),
            (
                "me",
                900,
                "The floor is about 60% of current, and I am fairly confident in that because it "
                "is the volume from customers who are contractually committed rather than "
                "growing.\n\n"
                "I would commit 24 months at 60% and buy the rest on demand. Committing to the "
                "forecast is how you end up paying for growth that did not arrive.",
                None,
            ),
            (
                "malcolm",
                200,
                "That is the answer I was hoping for and not the one I usually get. Doing that.",
                None,
            ),
        ],
    },
    {
        "subject": "Prescription refill question",
        "folder": "Archive",
        "with": "pharmacy",
        "days_ago": 30,
        "turns": [
            (
                "pharmacy",
                0,
                "Your refill is ready for collection. Please note this is the last refill on the "
                "current prescription and you will need a new one from your prescriber before "
                "the next.",
                None,
            ),
            ("me", 1200, "Understood, thank you. Collecting tomorrow.", None),
        ],
    },
    {
        "subject": "Stockholm trip, dates",
        "folder": "INBOX",
        "with": "hana",
        "days_ago": 14,
        "turns": [
            (
                "hana",
                0,
                "If you are coming over for the design week, the useful days are the 14th to the "
                "17th. Anything either side and half the team is away.\n\n"
                "You can stay with us, the spare room is genuinely spare.",
                None,
            ),
            (
                "me",
                1300,
                "14th to 17th works and I would love the spare room, thank you.\n\n"
                "I will book flights once the platform review is done, which is Thursday.",
                None,
            ),
            (
                "hana",
                2000,
                "The bakery on the corner has reopened, in case that affects your decision. It "
                "should.",
                None,
            ),
        ],
    },
    {
        "subject": "Neighbourhood: the parking permit thing",
        "folder": "INBOX",
        "with": "neighbour",
        "days_ago": 19,
        "turns": [
            (
                "neighbour",
                0,
                "Did you get the letter about resident permits? They want to charge for the "
                "second space and the consultation closes on the 5th.\n\n"
                "I am writing in. Thought you might want to as well.",
                None,
            ),
            (
                "me",
                480,
                "I got it. I will write in too. Do you have the reference number from the "
                "letter? Mine went in the recycling before I read the small print.",
                None,
            ),
            ("neighbour", 60, "PC-2026-0418. Deadline is 5pm on the 5th, not midnight.", None),
        ],
    },
    {
        "subject": "Insurance renewal, and a question",
        "folder": "Archive",
        "with": "insurance",
        "days_ago": 45,
        "turns": [
            (
                "insurance",
                0,
                "Your policy renews next month. The premium is up 8%, which is in line with the "
                "market rather than anything specific to you.\n\nRenewal documents attached.",
                ("pdf", "policy-renewal-2026.pdf", "Cardinal Mutual - renewal",
                 ["Policy: CM-8841-2026", "Premium: 1,284.00 annual",
                  "Excess: 500.00", "Contents cover: 60,000.00"]),
            ),
            (
                "me",
                1600,
                "Before I renew: the contents cover has been 60,000 for four years and I have "
                "not reassessed it once. Is there a sensible way to check whether that is still "
                "the right number, or do I just guess higher?",
                None,
            ),
            (
                "insurance",
                900,
                "There is a room by room worksheet, attached. Most people find they are under "
                "insured by about 20%, and the fix is cheaper than they expect.",
                ("csv", "contents-worksheet.csv",
                 ["room", "category", "estimate"],
                 [["Living room", "Electronics", "4200"],
                  ["Living room", "Furniture", "3800"],
                  ["Kitchen", "Appliances", "5100"],
                  ["Bedroom", "Clothing", "3400"],
                  ["Office", "Equipment", "6900"]]),
            ),
        ],
    },
    {
        "subject": "Ledger Quarterly: comment on the data retention piece?",
        "folder": "INBOX",
        "with": "editor",
        "days_ago": 13,
        "turns": [
            (
                "editor",
                0,
                "Avery, I am writing a piece on how long companies actually keep behavioural "
                "data versus how long their policies say they do. Would you go on the record?\n\n"
                "Happy to keep it to the principles rather than anything about Northwind "
                "specifically.\n\nConstance",
                None,
            ),
            (
                "me",
                1400,
                "On the record about principles, yes. Nothing about Northwind's own retention, "
                "and I would want to see my quotes before publication rather than after.\n\n"
                "The honest principle is that most retention policies describe an intention, and "
                "the actual retention is whatever the oldest backup happens to be.",
                None,
            ),
            (
                "editor",
                2200,
                "That last line is the piece, frankly. Quotes back to you before it runs, agreed.",
                None,
            ),
        ],
    },
    {
        "subject": "Coaching: the shoulder",
        "folder": "INBOX",
        "with": "trainer",
        "days_ago": 20,
        "turns": [
            (
                "trainer",
                0,
                "Following up on Thursday. The shoulder is not going to stop complaining while "
                "you keep loading it three times a week.\n\n"
                "Two weeks of the rehab set, then we reassess. Plan attached.",
                ("pdf", "shoulder-rehab-2wk.pdf", "Two week shoulder plan",
                 ["Mon: band external rotation 3x15, scap pulls 3x10",
                  "Wed: rest, mobility only",
                  "Fri: band external rotation 3x15, controlled hangs 3x20s",
                  "No overhead loading. None. Not even once."]),
            ),
            (
                "me",
                700,
                "Two weeks, no overhead. I will actually do it this time.",
                None,
            ),
            ("trainer", 30, "That is what you said in April.", None),
        ],
    },
    {
        "subject": "Weekend in Ridgemont?",
        "folder": "Archive",
        "with": "bea",
        "days_ago": 38,
        "turns": [
            (
                "bea",
                0,
                "Long shot: are you free the 22nd? Darius has a spare room and I have a car and "
                "no plans, which is a dangerous combination.",
                None,
            ),
            ("me", 300, "Cannot do the 22nd, I am on call. The 29th is wide open though.", None),
            ("bea", 400, "The 29th it is. I will tell Darius.", None),
        ],
    },
    {
        "subject": "Aunt Lilian: recipe, finally",
        "folder": "Archive",
        "with": "aunt",
        "days_ago": 60,
        "turns": [
            (
                "aunt",
                0,
                "You have asked three times so here it is, written down for once.\n\n"
                "The trick is not the sauce, it is that you must dry the tofu properly and "
                "nobody ever does. Twenty minutes under a weight, not five.\n\n"
                "Do not tell your mother I sent this.",
                None,
            ),
            ("me", 2400, "Twenty minutes. Understood. Your secret is safe.", None),
        ],
    },
    {
        "subject": "Handover while I am away",
        "folder": "INBOX",
        "with": "sam",
        "cc": ["dmitri", "tomas"],
        "days_ago": 15,
        "turns": [
            (
                "sam",
                0,
                "You are out the week of the 24th. Can you write the handover down rather than "
                "telling Dmitri in the corridor on your way out, which is what happened in May?",
                None,
            ),
            (
                "me",
                900,
                "Fair hit. Handover attached, and I have put the same thing in the runbook so it "
                "does not live only in this thread.\n\n"
                "The one thing I would flag: the backfill for the Kestrel migration is scheduled "
                "for the 26th. If it fails it is not urgent and it should be left until I am "
                "back rather than retried by someone who has not seen it before.",
                ("csv", "handover-week-24.csv",
                 ["item", "owner", "urgency", "notes"],
                 [["EU shard lag alert", "Dmitri", "page", "runbook RB-14"],
                  ["Kestrel backfill", "nobody", "leave it", "wait for Avery"],
                  ["Weekly export", "Tomas", "business hours", "automated, verify only"],
                  ["Access review chase", "Oluwaseun", "low", "due the 25th"]]),
            ),
            (
                "sam",
                200,
                "'Leave it' is a legitimate urgency level and more handovers should use it. "
                "Have a good week off.",
                None,
            ),
        ],
    },
]

# ---------------------------------------------------------------------------------
# One-off human mail. No replies, no thread.
# ---------------------------------------------------------------------------------

SINGLES: list[dict] = [
    {
        "from": "kwame",
        "subject": "Moving, new address",
        "folder": "INBOX",
        "days_ago": 17,
        "body": "New place from the 1st: 415 Winifred Street, apartment 3B. Same phone.\n\n"
        "Housewarming once there is furniture, which at current rate is November.",
    },
    {
        "from": "corin",
        "subject": "That article you mentioned",
        "folder": "INBOX",
        "days_ago": 4,
        "body": "I found it. It was not the one about attention, it was the one about "
        "measurement, which is probably why neither of us could remember the title.\n\n"
        "Short version: if you only measure what is easy to measure, you will slowly redefine "
        "the goal as the thing that is easy to measure. Felt relevant to your Thursday.",
    },
    {
        "from": "manda",
        "subject": "Wedding: save the date (properly this time)",
        "folder": "INBOX",
        "days_ago": 21,
        "body": "It is happening, it is the 18th of next April, and it is in Charleston.\n\n"
        "Formal invitations in the new year. This is just so you do not book anything.",
    },
    {
        "from": "dad",
        "subject": "Question about the laptop",
        "folder": "INBOX",
        "days_ago": 6,
        "body": "It keeps asking me to update and I keep saying later and now it says it will "
        "do it anyway. Is that bad? Should I let it? Your mother says to let it.",
    },
    {
        "from": "hugo",
        "subject": "Cake situation",
        "folder": "INBOX",
        "days_ago": 3,
        "body": "Do not tell Mei but the first attempt was a disaster and I am starting again "
        "Friday. Everything is under control.",
    },
    {
        "from": "yuki",
        "subject": "Odd result in the cohort model",
        "folder": "INBOX",
        "days_ago": 2,
        "body": "Retention for the March cohort is 4 points above every other cohort and I "
        "cannot find a reason for it in the data.\n\n"
        "Before I write it up as a finding I would like someone to tell me it is a bug, because "
        "results that good usually are.",
    },
    {
        "from": "nadia",
        "subject": "Roadmap: what I am cutting",
        "folder": "INBOX",
        "days_ago": 1,
        "body": "Cutting the saved-views feature from Q4. It is the third quarter it has been "
        "on the list and it has never once been the most important thing, which is information.\n\n"
        "Shout if that is wrong.",
    },
    {
        "from": "travel",
        "subject": "Stockholm: two routing options",
        "folder": "INBOX",
        "days_ago": 9,
        "body": "Option A is one stop via Amsterdam, arrives 09:20, costs more.\n"
        "Option B is two stops, arrives 21:45, saves about 300.\n\n"
        "For a four day trip I would pay the difference for option A, but it is your money.",
    },
    {
        "from": "paloma",
        "subject": "Well done on Thursday",
        "folder": "INBOX",
        "days_ago": 0,
        "body": "Short note: the split on slide 7 was noticed, and by the right person. Finance "
        "asked what the engineering half was and we had the number ready.\n\n"
        "That is the second time this quarter that being careful about attribution has paid off.",
    },
    {
        "from": "theo",
        "subject": "Do you still have my drill",
        "folder": "INBOX",
        "days_ago": 25,
        "body": "No pressure. It has been eight months.",
    },
    {
        "from": "mom",
        "subject": "Photos",
        "folder": "Archive",
        "days_ago": 47,
        "body": "Found a box of your grandmother's photographs while clearing the garage. Some "
        "of them have writing on the back in her hand.\n\n"
        "I am not going to scan them, I do not know how, but they are here when you are.",
    },
    {
        "from": "raf",
        "subject": "Migration plan, final",
        "folder": "Archive",
        "days_ago": 36,
        "body": "Final migration plan attached in the shared folder rather than by mail, since "
        "the last version bounced off your size limit.\n\nCutover is the 26th, as agreed.",
    },
    {
        "from": "tomas",
        "subject": "Post-incident notes, INC-2291",
        "folder": "Archive",
        "days_ago": 29,
        "body": "Notes are in the incident doc. The short version is that the alert fired "
        "correctly, was routed correctly, and was acknowledged by someone who was not in a "
        "position to act on it.\n\n"
        "That is a process finding, not a technology one, and I have written it up that way.",
    },
    {
        "from": "lena",
        "subject": "Sunday moved to 4",
        "folder": "INBOX",
        "days_ago": 5,
        "body": "Iris has a thing at 2 so book club moves to 4. Same place.",
    },
    {
        "from": "grace",
        "subject": "Countersigned SOW",
        "folder": "Archive",
        "days_ago": 11,
        "body": "Countersigned copy attached for your records. Nothing further needed from you.",
    },
]

# ---------------------------------------------------------------------------------
# HTML bulk. These carry a text/plain part too, because real newsletters do and a
# client that only renders one of the two should be caught doing it.
# ---------------------------------------------------------------------------------

NEWSLETTERS: list[dict] = [
    {
        "robot": "brief",
        "subject": "The Morning Brief: Tuesday",
        "days_ago": 0,
        "headline": "Three things worth your attention",
        "items": [
            ("Ports", "Container throughput at the three largest US ports fell for a second "
             "month, and the decline is now larger than seasonal adjustment explains."),
            ("Energy", "Grid operators in two states have asked large industrial customers to "
             "shift load away from the late afternoon peak."),
            ("Labour", "Job openings in transport and warehousing are down 11% year on year, "
             "the sharpest fall of any sector tracked."),
        ],
    },
    {
        "robot": "brief",
        "subject": "The Morning Brief: Monday",
        "days_ago": 1,
        "headline": "The week ahead",
        "items": [
            ("Rates", "The decision lands Wednesday. Consensus is no change, and the interest "
             "is entirely in the language rather than the number."),
            ("Earnings", "Four of the largest logistics operators report this week."),
            ("Weather", "A tropical system is expected to affect Gulf shipping from Thursday."),
        ],
    },
    {
        "robot": "devweekly",
        "subject": "Dev Weekly #412: the cost of a retry",
        "days_ago": 2,
        "headline": "Issue 412",
        "items": [
            ("The cost of a retry", "A long piece on retry storms, why exponential backoff is "
             "not enough on its own, and why jitter is the part everyone skips."),
            ("Reading", "Three papers on consistency models, one of which is actually readable."),
            ("Tools", "A small utility for diffing two database schemas that does not require "
             "connecting to either of them."),
        ],
    },
    {
        "robot": "devweekly",
        "subject": "Dev Weekly #411: schemas that outlive their authors",
        "days_ago": 9,
        "headline": "Issue 411",
        "items": [
            ("Schemas that outlive their authors", "On designing data formats for the decade "
             "after the team that wrote them has moved on."),
            ("Reading", "Two write-ups of migrations that went badly, both unusually honest."),
        ],
    },
    {
        "robot": "coffee",
        "subject": "Your subscription ships Thursday",
        "days_ago": 3,
        "headline": "Colombia, Huila. Washed.",
        "items": [
            ("This month", "Stone fruit, brown sugar, and a finish that goes slightly floral "
             "as it cools."),
            ("Grind", "Your grind is set to filter. Change it any time before Wednesday."),
        ],
    },
    {
        "robot": "conf",
        "subject": "StrataConf: the schedule is live",
        "days_ago": 12,
        "headline": "Four tracks, one of which is new",
        "items": [
            ("Schedule", "All 62 sessions are now listed, including the reliability track that "
             "was added after last year's feedback."),
            ("Your registration", "Confirmed. Badge collection opens at 08:00 on the first day."),
        ],
    },
    {
        "robot": "ridgeline",
        "subject": "End of season: up to 40% off",
        "days_ago": 6,
        "headline": "The gear we are clearing",
        "items": [
            ("Outerwear", "Last season's shells and insulation, while they last."),
            ("Footwear", "Selected approach shoes and trail runners."),
        ],
    },
    {
        "robot": "library",
        "subject": "What is new this month",
        "days_ago": 8,
        "headline": "New arrivals and events",
        "items": [
            ("New arrivals", "62 titles added across fiction and non-fiction."),
            ("Events", "Author talk on the 21st, free but ticketed."),
        ],
    },
]

# ---------------------------------------------------------------------------------
# Machine-generated one-liners. Bulk with a real shape: receipts, alerts, notices.
# Each entry is (robot, subject, body, days_ago, folder).
# ---------------------------------------------------------------------------------

NOTICES: list[tuple[str, str, str, int, str]] = [
    ("meridian", "Card ending 4417: $84.20 at Corner Coffee Roasters",
     "A card transaction of $84.20 was authorised at CORNER COFFEE ROASTERS.\n\n"
     "If this was not you, call the number on the back of your card. Do not reply to this "
     "message, it is not monitored.", 0, "INBOX"),
    ("meridian", "Card ending 4417: $212.66 at Ridgeline Outfitters",
     "A card transaction of $212.66 was authorised at RIDGELINE OUTFITTERS.", 4, "INBOX"),
    ("meridian", "Card ending 4417: $60.00 at Camden Auto",
     "A card transaction of $60.00 was authorised at SOUTHEND AUTO.", 21, "Archive"),
    ("meridian", "Statement ready for account ending 8802",
     "Your monthly statement is available. Closing balance and transaction detail are in the "
     "app.", 7, "INBOX"),
    ("meridian", "Unusual sign-in blocked",
     "A sign-in attempt from a device we do not recognise was blocked. No action is needed "
     "unless this was you, in which case sign in again from a trusted device.", 33, "Archive"),
    ("ridgeline", "Order RG-88214 confirmed",
     "Thank you for your order. Two items, dispatching within one working day.\n\n"
     "Order total: $212.66", 4, "INBOX"),
    ("ridgeline", "Order RG-88214 has shipped",
     "Your order is on its way. Tracking is available through the carrier.", 3, "INBOX"),
    ("shipping", "Parcel PW-4471-9920 out for delivery",
     "Your parcel is out for delivery today between 11:00 and 15:00.", 2, "INBOX"),
    ("shipping", "Parcel PW-4471-9920 delivered",
     "Delivered at 12:41 and left with a neighbour at number 14.", 2, "INBOX"),
    ("shipping", "Parcel PW-4390-1188 delivered",
     "Delivered at 09:03, signed for.", 18, "Archive"),
    ("forge", "[northwind/ingest] MR !2214 was merged",
     "Dmitri Sokolov merged merge request !2214 into main.\n\n"
     "  Restore retention window offset, with a regression test\n\n"
     "3 files changed, 84 insertions, 6 deletions.", 3, "INBOX"),
    ("forge", "[northwind/ingest] You were mentioned in MR !2214",
     "Dmitri Sokolov mentioned you:\n\n"
     "  @avery this is the fix we discussed, test included as asked.", 4, "INBOX"),
    ("forge", "[northwind/platform] Pipeline failed on main",
     "Pipeline #99182 failed at stage: integration.\n\n"
     "  test_backfill_resumes_after_interrupt - assertion error", 5, "INBOX"),
    ("forge", "[northwind/platform] Pipeline passed on main",
     "Pipeline #99184 passed. All 6 stages green.", 5, "Archive"),
    ("forge", "[northwind/docs] MR !318 needs your review",
     "Hana Lindqvist requested your review on:\n\n  Rewrite the empty state copy", 2, "INBOX"),
    ("status", "Scheduled maintenance: ingest, Sunday 02:00-04:00 UTC",
     "Ingest will be unavailable for up to 30 minutes inside this window. Queued events are "
     "held and replayed, no data is lost.", 6, "INBOX"),
    ("status", "Resolved: elevated latency in the EU region",
     "Between 02:10 and 14:40 UTC the EU region processed events with up to 40 minutes of "
     "delay. No events were lost. A full write-up will follow.", 4, "Archive"),
    ("utility", "Your bill is ready: $118.44",
     "Billing period: 1 July to 31 July. Payment is due on the 28th.\n\n"
     "Usage was 9% higher than the same period last year.", 10, "INBOX"),
    ("utility", "Payment received, thank you",
     "We have received your payment of $118.44.", 9, "Archive"),
    ("utility", "Your bill is ready: $102.19",
     "Billing period: 1 June to 30 June. Payment is due on the 28th.", 41, "Archive"),
    ("streaming", "Your plan renews on the 24th",
     "Your subscription renews on the 24th at $14.99 per month. You can change or cancel at "
     "any time before then.", 8, "INBOX"),
    ("streaming", "Price change from November",
     "From November your plan will be $16.99 per month. You do not need to do anything to "
     "stay on it.", 26, "Archive"),
    ("library", "Item due in three days",
     "One item is due back on the 21st. Renew online unless it is reserved by another reader.",
     3, "INBOX"),
    ("library", "Item overdue",
     "One item was due on the 4th and is now overdue. Charges begin after 14 days.", 14, "Archive"),
    ("gym", "Your membership: no change this month",
     "Your membership renewed at the same rate. Next payment on the 3rd.", 15, "Archive"),
    ("gym", "Route reset this Thursday",
     "The lead wall is closed from 10:00 to 16:00 on Thursday for the reset. Bouldering is "
     "open as usual.", 6, "INBOX"),
    ("coffee", "Your order shipped",
     "Your monthly bag is on its way and should arrive within two working days.", 3, "Archive"),
    ("conf", "Your StrataConf registration is confirmed",
     "Registration reference SC-2026-40118. Keep this message, it is your proof of purchase.",
     23, "Archive"),
    ("calendar", "Reminder: platform review tomorrow at 10:00",
     "Platform review, tomorrow at 10:00, room 4B and video.\n\nOrganiser: Paloma Raghunathan",
     3, "Archive"),
    ("calendar", "Reminder: interview, Tuesday at 11:00",
     "Systems design interview, Tuesday at 11:00, video only.\n\nOrganiser: Ellen Vaase",
     6, "Archive"),
]

# ---------------------------------------------------------------------------------
# Junk. Obvious, harmless, and pointed only at reserved domains.
# ---------------------------------------------------------------------------------

JUNK: list[tuple[int, str, str, int]] = [
    (0, "RE: RE: your outstanding settlement",
     "Dear Beneficiary,\n\nYour funds have been APPROVED for immediate release. We require "
     "only a small processing fee to complete the transfer to your account.\n\n"
     "Reply urgently with your details.", 1),
    (1, "*** YOU HAVE BEEN SELECTED ***",
     "CONGRATULATIONS!!! Your email address was selected in our international draw. To claim "
     "your prize please confirm your identity within 48 HOURS or forfeit.", 3),
    (2, "Quick question about your website",
     "Hi there,\n\nI was looking at your site and noticed it is not ranking for any of your "
     "main keywords. I can fix this in 30 days, guaranteed, first page or your money back.\n\n"
     "Interested?", 5),
    (3, "FINAL NOTICE: your vehicle warranty",
     "This is your FINAL NOTICE regarding your vehicle's extended warranty. Coverage on your "
     "vehicle is about to expire. Press 1 to speak to a specialist.", 6),
    (4, "17,400% gains this week (proof inside)",
     "Our signals group returned 17,400% last week alone. Members are quitting their jobs.\n\n"
     "Doors close Friday. Do not miss this.", 8),
    (5, "URGENT: benefits enrolment closes today",
     "Your HR benefits enrolment is incomplete. Sign in immediately using the link below to "
     "avoid loss of coverage.\n\nThis is your final reminder.", 11),
    (6, "Delivery failed: action required",
     "We attempted delivery of your package but were unable to complete it. A redelivery fee "
     "of $2.99 is required to release your item.", 13),
    (7, "Confidential business proposal",
     "Good day,\n\nI am contacting you in strict confidence regarding a transfer of "
     "$18,500,000 USD held in a dormant account. I require a trustworthy foreign partner.", 19),
    (8, "Someone is waiting to meet you",
     "3 new people near you want to connect tonight. Click to see who.", 24),
]

# ---------------------------------------------------------------------------------
# Drafts. Unfinished on purpose: half a sentence is a real draft, a polished one is not.
# ---------------------------------------------------------------------------------

DRAFTS: list[dict] = [
    {
        "to": ["paloma"],
        "subject": "Q4 headcount, thinking out loud",
        "days_ago": 1,
        "body": "Paloma,\n\nBefore the Q4 planning session I want to put a view in writing so "
        "it is not just a reaction in the room.\n\n"
        "We do not need two more engineers. We need one engineer and the thing nobody wants to "
        "own, which is",
    },
    {
        "to": ["mei"],
        "subject": "Re: Noor's birthday, the weekend of the 12th",
        "days_ago": 5,
        "body": "Found a geology kit that comes with a real hand lens rather than a plastic "
        "one. Ordering it unless you think that is too",
    },
    {
        "to": ["recruiter"],
        "subject": "Re: Following up re: Staff Data Engineer role",
        "days_ago": 26,
        "body": "Gideon,\n\nThis is the fourth message about the same role in two months and I "
        "have not replied to any of them, which is itself an answer.",
    },
    {
        "to": ["dmitri", "tomas"],
        "subject": "Runbook: what to do when the lag alert is wrong",
        "days_ago": 12,
        "body": "Draft runbook section. The alert fires on absolute lag, which is wrong during "
        "a backfill because the lag is expected and the alert cannot tell the difference.\n\n"
        "Proposal:\n\n1. suppress during a declared backfill window\n2. ",
    },
    {
        "to": ["editor"],
        "subject": "Re: Ledger Quarterly: comment on the data retention piece?",
        "days_ago": 13,
        "body": "One more thought for the piece, use it or do not:\n\nRetention policy is the "
        "only company document where the gap between what it says and what is true is",
    },
    {
        "to": ["mom", "dad"],
        "subject": "October",
        "days_ago": 2,
        "body": "I am going to try to come out in October rather than waiting for the holidays. "
        "Nothing is booked. Do not tell Mei yet because",
    },
]
