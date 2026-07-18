import VisibilityIcon from '@mui/icons-material/Visibility';
import VisibilityOffIcon from '@mui/icons-material/VisibilityOff';
import { IconButton, InputAdornment, TextField, Tooltip } from '@mui/material';
import type { TextFieldProps } from '@mui/material';
import { useState } from 'react';

type SecretTextFieldProps = Omit<TextFieldProps, 'type'>;

/** Sensitive input with an explicit, local-only reveal toggle. */
export function SecretTextField({ InputProps, ...props }: SecretTextFieldProps) {
  const [visible, setVisible] = useState(false);
  const label = visible ? '隐藏内容' : '显示内容';
  return (
    <TextField
      {...props}
      type={visible ? 'text' : 'password'}
      InputProps={{
        ...InputProps,
        endAdornment: (
          <>
            {InputProps?.endAdornment}
            <InputAdornment position="end">
              <Tooltip title={label}>
                <IconButton
                  edge="end"
                  aria-label={label}
                  onClick={() => setVisible((current) => !current)}
                  onMouseDown={(event) => event.preventDefault()}
                >
                  {visible ? <VisibilityOffIcon /> : <VisibilityIcon />}
                </IconButton>
              </Tooltip>
            </InputAdornment>
          </>
        ),
      }}
    />
  );
}
