'use client';

import React, { createContext, useContext, useState, useCallback } from 'react';

type ToastType = 'success' | 'info' | 'warning' | 'error';

interface Toast {
  id: string;
  message: string;
  type: ToastType;
}

interface ToastContextType {
  showToast: (message: string, type?: ToastType) => void;
}

const ToastContext = createContext<ToastContextType | undefined>(undefined);

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const showToast = useCallback((message: string, type: ToastType = 'success') => {
    const id = Math.random().toString(36).substring(2, 9);
    setToasts((prev) => [...prev, { id, message, type }]);

    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, 4000);
  }, []);

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      <div
        style={{
          position: 'fixed',
          bottom: '24px',
          right: '24px',
          zIndex: 9999,
          display: 'flex',
          flexDirection: 'column',
          gap: '10px',
          pointerEvents: 'none',
        }}
      >
        {toasts.map((toast) => {
          const borderColor =
            toast.type === 'success'
              ? 'var(--success)'
              : toast.type === 'warning'
              ? 'var(--warning)'
              : toast.type === 'error'
              ? 'var(--danger)'
              : 'var(--primary)';

          const icon =
            toast.type === 'success' ? '?' : toast.type === 'error' ? '?' : toast.type === 'warning' ? '?' : '?';

          return (
            <div
              key={toast.id}
              style={{
                pointerEvents: 'auto',
                background: 'var(--surface-raised)',
                borderWidth: '1px',
                borderStyle: 'solid',
                borderColor: borderColor,
                boxShadow: '0 10px 25px -5px rgba(0, 0, 0, 0.5), 0 0 15px rgba(6, 182, 212, 0.1)',
                color: 'var(--text-main)',
                padding: '12px 18px',
                borderRadius: '10px',
                fontSize: '13px',
                fontWeight: 500,
                display: 'flex',
                alignItems: 'center',
                gap: '10px',
                maxWidth: '380px',
              }}
            >
              <span
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  width: '20px',
                  height: '20px',
                  borderRadius: '50%',
                  background: borderColor,
                  color: '#000',
                  fontSize: '11px',
                  fontWeight: 800,
                  flexShrink: 0,
                }}
              >
                {icon}
              </span>
              <span>{toast.message}</span>
            </div>
          );
        })}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToast must be used within a ToastProvider');
  }
  return context;
}
