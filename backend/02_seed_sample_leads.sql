-- =====================================================================
--  SAMPLE LEADS — for testing only. Do NOT run against production.
--  These are fabricated records; the PANs are structurally valid but
--  belong to nobody.
--
--  *** RUN THIS LAST, NOT SECOND. ***
--
--  Despite the 02 in the filename, this file inserts `address`, and that
--  column is not created until 03. Running the files in numeric order
--  therefore fails with: column "address" of relation "leads" does not
--  exist. The working order on a fresh project is:
--
--      01 → 03 → 04 → 05 → 06 → 02
--
--  The number is kept only so existing notes and links still make sense.
-- =====================================================================

insert into public.leads
  (name, pan, mobile, email, dob, annual_income_range, company, ici_cr_lmt, address, batch)
values
  ('Rohit Sharma',      'ABCPS1234K', '9876543210', 'rohit.sharma@example.com',   '1988-04-30', '10L - 15L',  'Infosys Ltd',            250000.00, 'Flat 402, Skyline Residency, 14th Cross, Indiranagar, Bengaluru, Karnataka 560038', 'DEMO_BATCH_01'),
  ('Priya Nair',        'BXQPN5678L', '9812345678', 'priya.nair@example.com',     '1992-11-12', '15L - 25L',  'Tata Consultancy Svcs',  400000.00, 'B-7, Palm Grove Apartments, Marine Drive, Kochi, Kerala 682031', 'DEMO_BATCH_01'),
  ('Amit Deshpande',    'CDEPD9012M', '9900112233', 'amit.d@example.com',         '1985-01-22', '5L - 10L',   'Wipro Technologies',     150000.00, '221, Shivaji Nagar, Near FC Road, Pune, Maharashtra 411005', 'DEMO_BATCH_01'),
  ('Sneha Iyer',        'DFGPI3456N', '9765432100', 'sneha.iyer@example.com',     '1995-07-08', '10L - 15L',  'HCL Technologies',       300000.00, 'No. 18, 3rd Main, Adyar, Chennai, Tamil Nadu 600020', 'DEMO_BATCH_01'),
  ('Vikram Singh',      'EHIPS7890P', '9988776655', 'vikram.singh@example.com',   '1979-03-15', '25L+',       'Reliance Industries',    750000.00, 'Villa 9, Emerald Heights, Vasant Kunj, New Delhi 110070', 'DEMO_BATCH_01'),
  ('Ananya Bose',       'FJKPB2345Q', '9871234560', 'ananya.bose@example.com',    '1990-09-25', '15L - 25L',  'Accenture India',        500000.00, '55/2, Salt Lake Sector V, Kolkata, West Bengal 700091', 'DEMO_BATCH_01'),
  ('Karthik Reddy',     'GLMPR6789R', '9845012345', 'karthik.r@example.com',      '1993-12-01', '5L - 10L',   'Tech Mahindra',          125000.00, 'Plot 12, Jubilee Hills Road No. 36, Hyderabad, Telangana 500033', 'DEMO_BATCH_01'),
  ('Meera Joshi',       'HNOPJ0123S', '9820011223', 'meera.joshi@example.com',    '1987-06-18', '10L - 15L',  'Larsen & Toubro',        275000.00, 'A-304, Runwal Greens, Mulund West, Mumbai, Maharashtra 400080', 'DEMO_BATCH_01'),
  ('Arjun Menon',       'IPQPM4567T', '9633445566', 'arjun.menon@example.com',    '1991-02-27', '15L - 25L',  'Cognizant',              450000.00, '7, Panampilly Nagar, Ernakulam, Kerala 682036', 'DEMO_BATCH_01'),
  ('Divya Kapoor',      'JRSPK8901U', '9811223344', 'divya.kapoor@example.com',   '1994-10-05', '25L+',       'Deloitte India',         800000.00, 'C-11, Sushant Lok Phase I, Gurugram, Haryana 122009', 'DEMO_BATCH_01');

-- ---------------------------------------------------------------------
-- Promote a user to supervisor (run AFTER the supervisor has created them in
-- Authentication → Users; see section 6 of CLAUDE.md):
--
--   update public.profiles set role = 'supervisor' where login_id = 'rahul.k';
-- ---------------------------------------------------------------------
