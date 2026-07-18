import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { SecretTextField } from './SecretTextField';

describe('SecretTextField', () => {
  it('hides the value by default and reveals it only after the eye toggle', async () => {
    const user = userEvent.setup();
    render(<SecretTextField label="API Key" value="sk-test-value" onChange={() => undefined} />);

    const input = screen.getByLabelText('API Key');
    expect(input).toHaveAttribute('type', 'password');
    await user.click(screen.getByRole('button', { name: '显示内容' }));
    expect(input).toHaveAttribute('type', 'text');
    expect(screen.getByRole('button', { name: '隐藏内容' })).toBeInTheDocument();
  });
});
