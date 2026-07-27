INSERT INTO users (
    id,
    email,
    password_hash,
    full_name,
    role,
    roll_number,
    is_active,
    created_at,
    updated_at
) VALUES (
    gen_random_uuid(),
    'admin@codepulse.dev',
    '$2a$12$SDAtClZvyF126tGnNci4Nu/8IK3WkrlAcfTTKQffbXiWoz101rRl6',
    'System Admin',
    'ADMIN',
    NULL,
    TRUE,
    now(),
    now()
) ON CONFLICT (email) DO NOTHING;