import RefreshIcon from '@mui/icons-material/Refresh';
import { Box, Button, Stack, Tab, Tabs } from '@mui/material';
import { useCallback, useEffect, useState } from 'react';

import { api } from './api';
import type { AccountsOverview } from './types';
import { ErrorBlock, LoadingBlock, SectionTitle } from './ui';
import { TradingAccountOverview } from './TradingAccountOverview';
import { TradingActivityPanel } from './TradingActivityPanel';
import { replaceWorkspaceLocation, workspaceSubview } from './workspaceLocation';

type TradingView = 'overview' | 'activity';
const tradingViews: readonly TradingView[] = ['overview', 'activity'];

export function TradingPage() {
  const [view, setView] = useState<TradingView>(() => workspaceSubview('trading', tradingViews, 'overview'));
  const [accounts, setAccounts] = useState<AccountsOverview | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);

  const loadAccounts = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setAccounts(await api.accounts('ALL'));
    } catch (cause) {
      setError(cause);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void loadAccounts(); }, [loadAccounts]);
  const changeView = (next: TradingView) => {
    setView(next);
    replaceWorkspaceLocation('trading', next);
  };

  if (loading && !accounts) return <LoadingBlock label="正在同步模拟账户与永久交易仓库" />;
  if (error !== null && !accounts) return <ErrorBlock error={error} />;

  return <Stack spacing={2.5}>
    {error !== null && <ErrorBlock error={error} />}
    <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={1}>
      <SectionTitle title="模拟账户与交易审计" />
      <Button startIcon={<RefreshIcon />} onClick={() => void loadAccounts()} disabled={loading}>同步账户</Button>
    </Stack>
    <Box sx={{ borderBottom: '1px solid', borderColor: 'divider' }}>
      <Tabs value={view} onChange={(_event, value: TradingView) => changeView(value)}>
        <Tab value="overview" label="账户概览" />
        <Tab value="activity" label="操作历史" />
      </Tabs>
    </Box>
    {accounts && view === 'overview' && <TradingAccountOverview initialAccounts={accounts} onAccountsChanged={setAccounts} />}
    {accounts && view === 'activity' && <TradingActivityPanel accounts={accounts.accounts} />}
  </Stack>;
}
