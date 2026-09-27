-- Add academic fields for CANDIDATE users
ALTER TABLE users
ADD COLUMN academic_year INT,
ADD COLUMN branch VARCHAR(10),
ADD COLUMN division VARCHAR(5),
ADD COLUMN batch VARCHAR(5);
