import RefreshIcon from '@mui/icons-material/Refresh';
import { Box, Button, Paper, Stack, Tab, Tabs, Typography } from '@mui/material';
import { useCallback, useEffect, useState } from 'react';

import { api } from './api';
import type { AccountsOverview } from './types';
import { ErrorBlock, LoadingBlock } from './ui';
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

  return <Stack spacing={2}>
    {error !== null && <ErrorBlock error={error} />}
    <Paper variant="outlined" sx={{ display: 'flex', alignItems: { xs: 'stretch', sm: 'center' }, flexDirection: { xs: 'column', sm: 'row' }, px: 1, minHeight: 52 }}>
      <Box sx={{ px: 1, py: { xs: 1, sm: 0 }, minWidth: 190 }}><Typography variant="subtitle1">模拟账户与交易审计</Typography><Typography variant="caption" color="text.secondary">验证研究结论与永久保存交易事实</Typography></Box>
      <Tabs value={view} onChange={(_event, value: TradingView) => changeView(value)} sx={{ flex: 1, minWidth: 0 }}>
        <Tab value="overview" label="账户概览" />
        <Tab value="activity" label="操作历史" />
      </Tabs>
      <Button startIcon={<RefreshIcon />} onClick={() => void loadAccounts()} disabled={loading} sx={{ m: { xs: 1, sm: .5 }, alignSelf: { xs: 'stretch', sm: 'center' } }}>同步账户</Button>
    </Paper>
    {accounts && view === 'overview' && <TradingAccountOverview initialAccounts={accounts} onAccountsChanged={setAccounts} />}
    {accounts && view === 'activity' && <TradingActivityPanel accounts={accounts.accounts} />}
  </Stack>;
}
