import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import App from './App';
import './styles.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#126a5a',
          colorInfo: '#126a5a',
          borderRadius: 6,
          fontFamily: 'Aptos, "Microsoft YaHei", "Noto Sans SC", sans-serif'
        },
        components: {
          Layout: {
            bodyBg: '#f4f6f5',
            siderBg: '#17231f',
            headerBg: '#ffffff'
          },
          Table: {
            headerBg: '#eef3f0'
          }
        }
      }}
    >
      <App />
    </ConfigProvider>
  </React.StrictMode>
);

