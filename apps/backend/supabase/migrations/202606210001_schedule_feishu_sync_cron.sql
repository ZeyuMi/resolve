-- Keep server-managed Feishu calendars fresh without requiring an app-side
-- manual sync action.

create extension if not exists pg_cron with schema extensions;
create extension if not exists pg_net with schema extensions;

do $$
begin
  if exists (select 1 from cron.job where jobname = 'resolve-feishu-sync-cron') then
    perform cron.unschedule('resolve-feishu-sync-cron');
  end if;
end
$$;

select cron.schedule(
  'resolve-feishu-sync-cron',
  '*/5 * * * *',
  $$
    select net.http_post(
      url := 'https://pfghmlcstwhykexuaimj.supabase.co/functions/v1/feishu-sync-cron',
      headers := '{"Content-Type":"application/json"}'::jsonb,
      body := '{}'::jsonb
    );
  $$
);
