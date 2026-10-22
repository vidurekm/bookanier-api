-- Built-in templates are required by the setup wizard and must survive data cleanup.
-- Restore missing rows without overwriting existing template configuration.
INSERT INTO business_templates(template_key, booking_model, label, steps, defaults) VALUES
('SALON', 'APPOINTMENT', 'Appointments',
 '[{"id":"locations","when":"always"},{"id":"team","when":"always"},{"id":"services","when":"always"},{"id":"resources","when":"always"},{"id":"hours","when":"always"}]',
 '{"showStaffChoice":true,"trackResources":false,"resourceName":"Chair","resourceCount":1,"resourceCapacity":1}'),
('RESTAURANT', 'RESOURCE', 'Space reservations',
 '[{"id":"locations","when":"always"},{"id":"resources","when":"always"},{"id":"services","when":"always"},{"id":"hours","when":"always"}]',
 '{"trackResources":true,"resourceName":"Table","resourceCount":8,"resourceCapacity":4,"showStaffChoice":false,"serviceName":"Table reservation","serviceDuration":90}'),
('RENTAL', 'RENTAL', 'Equipment rentals',
 '[{"id":"locations","when":"always"},{"id":"resources","when":"always"},{"id":"services","when":"always"},{"id":"hours","when":"always"}]',
 '{"trackResources":true,"resourceName":"Equipment","resourceCount":3,"resourceCapacity":1,"showStaffChoice":false,"serviceName":"Equipment rental","serviceDuration":60}'),
('EVENTS', 'EVENT', 'Classes and events',
 '[{"id":"locations","when":"always"},{"id":"team","when":"always"},{"id":"events","when":"always"},{"id":"hours","when":"always"}]',
 '{"eventCapacity":12,"useWaitlist":false}'),
('GENERAL', 'APPOINTMENT', 'Other services',
 '[{"id":"locations","when":"always"},{"id":"team","when":"always"},{"id":"services","when":"always"},{"id":"resources","when":"always"},{"id":"hours","when":"always"}]',
 '{}')
ON CONFLICT (template_key) DO NOTHING;
