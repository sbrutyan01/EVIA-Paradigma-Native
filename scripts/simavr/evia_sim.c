/*
 * Runs an AVR program (Intel HEX) in simavr and copies what it sends on the UART to stdout,
 * byte for byte. Used by scripts/check-boards.sh to compare bare-metal AVR code with the
 * interpreter. Stops when the program sleeps with interrupts off (EVIA's rt_end), crashes,
 * or after a time limit.
 *
 *   evia_sim MCU program.hex [options]
 *     --seconds N      stop after N seconds of simulated time (default 120)
 *     --watch D5       report every change of output pin PD5 on stderr: "[1000 ms] PD5 1"
 *     --button D4      a push button to ground on PD4, pressed during the second half of every
 *                      2-second period - the same button the interpreter's simulated board has
 *     --adc 7          feed ADC channel 7 with the interpreter's analog signal: 0..1023..0 over 4 s
 *     --uart-loop      wire the UART's TX to its RX (what the interpreter's serial port does)
 *     --input FILE     type FILE into the first UART, a byte every millisecond (a program's Input)
 *     --i2c 80         an I2C device at 7-bit address 80 with 256 registers, all 0 at the start - the
 *                      device the interpreter's simulated board has (SPI needs nothing: the chip's own
 *                      model already returns the byte it sent)
 *
 * Built against simavr's simulator core (without its ELF loader, so libelf isn't needed).
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "sim_avr.h"
#include "sim_hex.h"
#include "avr_uart.h"
#include "avr_ioport.h"
#include "sim_cycle_timers.h"
#include "avr_adc.h"
#include "avr_twi.h"

/* sim_hex.c can also load ELF files; evia_sim only loads HEX, so these are never called. */
struct elf_firmware_t;
void sim_default_mcu(struct elf_firmware_t *firmware, const char *file) {
    (void) firmware;
    (void) file;
}
int elf_read_firmware(const char *file, struct elf_firmware_t *firmware) {
    (void) file;
    (void) firmware;
    return -1;
}

static avr_t *avr;
static avr_irq_t *uart_in[2];
static int uart_loop;

static void uart_out(struct avr_irq_t *irq, uint32_t value, void *param) {
    (void) irq;
    putchar((int) (value & 0xFF));
    if (uart_loop && uart_in[(intptr_t) param]) avr_raise_irq(uart_in[(intptr_t) param], value & 0xFF);
}

/* ---- the I2C device: 256 registers; a write is  register, value  and a read continues from the register */
static uint8_t i2c_regs[256];
static uint8_t i2c_addr;        /* 8-bit form of the device address (7-bit address << 1) */
static int i2c_selected;
static int i2c_index;
static uint8_t i2c_reg;
static avr_irq_t *i2c_irq;      /* two IRQs: [TWI_IRQ_INPUT] to the chip, [TWI_IRQ_OUTPUT] from the chip */

static void i2c_hook(struct avr_irq_t *irq, uint32_t value, void *param) {
    (void) irq;
    (void) param;
    avr_twi_msg_irq_t v;
    v.u.v = value;
    if (v.u.twi.msg & TWI_COND_STOP) {
        i2c_selected = 0;
        i2c_index = 0;
    }
    if (v.u.twi.msg & TWI_COND_START) {
        i2c_selected = 0;
        i2c_index = 0;
        if ((v.u.twi.addr & ~1) == i2c_addr) {
            i2c_selected = v.u.twi.addr;
            avr_raise_irq(i2c_irq + TWI_IRQ_INPUT, avr_twi_irq_msg(TWI_COND_ACK, i2c_selected, 1));
        }
    }
    if (i2c_selected) {
        if (v.u.twi.msg & TWI_COND_WRITE) {
            avr_raise_irq(i2c_irq + TWI_IRQ_INPUT, avr_twi_irq_msg(TWI_COND_ACK, i2c_selected, 1));
            if (i2c_index == 0) i2c_reg = v.u.twi.data;
            else i2c_regs[i2c_reg++] = v.u.twi.data;
            i2c_index++;
        }
        if (v.u.twi.msg & TWI_COND_READ) {
            avr_raise_irq(i2c_irq + TWI_IRQ_INPUT, avr_twi_irq_msg(TWI_COND_READ, i2c_selected, i2c_regs[i2c_reg++]));
        }
    }
}

static void i2c_attach(int addr7) {
    static const char *names[2] = { [TWI_IRQ_INPUT] = "8>device.out", [TWI_IRQ_OUTPUT] = "32<device.in" };
    i2c_addr = (uint8_t) (addr7 << 1);
    i2c_irq = avr_alloc_irq(&avr->irq_pool, 0, 2, names);
    avr_irq_register_notify(i2c_irq + TWI_IRQ_OUTPUT, i2c_hook, NULL);
    avr_connect_irq(i2c_irq + TWI_IRQ_INPUT, avr_io_getirq(avr, AVR_IOCTL_TWI_GETIRQ(0), TWI_IRQ_INPUT));
    avr_connect_irq(avr_io_getirq(avr, AVR_IOCTL_TWI_GETIRQ(0), TWI_IRQ_OUTPUT), i2c_irq + TWI_IRQ_OUTPUT);
}

static unsigned long now_ms(void) {
    return (unsigned long) (avr->cycle / (avr->frequency / 1000));
}

static void pin_changed(struct avr_irq_t *irq, uint32_t value, void *param) {
    (void) irq;
    fprintf(stderr, "[%lu ms] P%s %u\n", now_ms(), (const char *) param, value & 1);
}

/* The button: released (pin pulled high) except during the second half of each 2-second period. */
static avr_irq_t *button;
static char button_port;
static int button_bit;
static int button_down = -1;

static avr_cycle_count_t button_tick(struct avr_t *a, avr_cycle_count_t when, void *param) {
    (void) a;
    (void) param;
    int down = now_ms() % 2000 >= 1000;
    if (down != button_down) {
        button_down = down;
        /* a pressed button holds the pin low, as an external pull: otherwise a write to its port (the LED on
           another pin of it) would put the pin's pull-up level back */
        avr_ioport_external_t e = {.name = button_port, .mask = down ? 1 << button_bit : 0, .value = 0};
        avr_ioctl(avr, AVR_IOCTL_IOPORT_SET_EXTERNAL(button_port), &e);
        avr_raise_irq(button, down ? 0 : 1);
    }
    return when + avr->frequency / 1000;   /* again in 1 ms */
}

/* The analog signal: a triangle wave 0..1023..0 with a 4.096 s period, as millivolts (5 V reference). */
static avr_irq_t *adc;

static avr_cycle_count_t adc_tick(struct avr_t *a, avr_cycle_count_t when, void *param) {
    (void) a;
    (void) param;
    unsigned long t = now_ms() % 4096;
    unsigned long value = t < 2048 ? t / 2 : (4095 - t) / 2;
    avr_raise_irq(adc, (uint32_t) ((value * 5000 + 1022) / 1023));
    return when + avr->frequency / 1000;
}

/* ---- --input: the file's bytes into UART 0, one a millisecond (slower than the chip reads them) */
static FILE *input;

static avr_cycle_count_t input_tick(struct avr_t *a, avr_cycle_count_t when, void *param) {
    (void) a;
    (void) param;
    int c = fgetc(input);
    if (c == EOF) return 0;
    avr_raise_irq(uart_in[0], (uint32_t) c);
    return when + avr->frequency / 1000;
}

static avr_irq_t *pin_irq(const char *name) {
    if (strlen(name) != 2 || name[1] < '0' || name[1] > '7') {
        fprintf(stderr, "evia_sim: pin names look like D4 or B5, not %s\n", name);
        exit(2);
    }
    avr_irq_t *irq = avr_io_getirq(avr, AVR_IOCTL_IOPORT_GETIRQ(name[0]), name[1] - '0');
    if (!irq) {
        fprintf(stderr, "evia_sim: this chip has no port %c\n", name[0]);
        exit(2);
    }
    return irq;
}

static void quiet_log(avr_t *avr, const int level, const char *format, va_list ap) {
    (void) avr;
    if (level <= LOG_ERROR) vfprintf(stderr, format, ap);
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: evia_sim MCU program.hex [--seconds N] [--watch D5] [--button D4] [--adc 0] [--uart-loop] [--i2c 80] [--input FILE]\n");
        return 2;
    }
    double seconds = 120;
    avr = avr_make_mcu_by_name(argv[1]);
    if (!avr) {
        fprintf(stderr, "evia_sim: unknown MCU %s\n", argv[1]);
        return 2;
    }
    avr_global_logger_set(quiet_log);
    avr_init(avr);
    avr->frequency = 16000000;
    avr->vcc = avr->avcc = 5000;   /* millivolts: a 5 V board */

    uint32_t size = 0, start = 0;
    uint8_t *code = read_ihex_file(argv[2], &size, &start);
    if (!code) {
        fprintf(stderr, "evia_sim: can't read %s\n", argv[2]);
        return 2;
    }
    avr_loadcode(avr, code, size, start);
    free(code);
    avr->pc = 0;

    const char names[] = {'0', '1'};
    for (unsigned i = 0; i < sizeof names; i++) {
        uint32_t flags = 0;
        if (avr_ioctl(avr, AVR_IOCTL_UART_GET_FLAGS(names[i]), &flags) < 0) continue;   /* no such UART */
        flags &= ~AVR_UART_FLAG_STDIO;
        avr_ioctl(avr, AVR_IOCTL_UART_SET_FLAGS(names[i]), &flags);
        avr_irq_t *out = avr_io_getirq(avr, AVR_IOCTL_UART_GETIRQ(names[i]), UART_IRQ_OUTPUT);
        if (out) avr_irq_register_notify(out, uart_out, (void *) (intptr_t) i);
        uart_in[i] = avr_io_getirq(avr, AVR_IOCTL_UART_GETIRQ(names[i]), UART_IRQ_INPUT);
    }

    for (int i = 3; i < argc; i += 2) {
        if (!strcmp(argv[i], "--uart-loop")) {
            uart_loop = 1;
            i--;
            continue;
        }
        if (i + 1 >= argc) {
            fprintf(stderr, "evia_sim: option %s needs a value\n", argv[i]);
            return 2;
        }
        if (!strcmp(argv[i], "--i2c")) {
            i2c_attach(atoi(argv[i + 1]));
        } else if (!strcmp(argv[i], "--input")) {
            input = fopen(argv[i + 1], "rb");
            if (!input || !uart_in[0]) {
                fprintf(stderr, "evia_sim: can't type %s into the UART\n", argv[i + 1]);
                return 2;
            }
            avr_cycle_timer_register(avr, avr->frequency / 100, input_tick, NULL);
        } else if (!strcmp(argv[i], "--seconds")) {
            seconds = atof(argv[i + 1]);
        } else if (!strcmp(argv[i], "--watch")) {
            avr_irq_register_notify(pin_irq(argv[i + 1]), pin_changed, argv[i + 1]);
        } else if (!strcmp(argv[i], "--button")) {
            button = pin_irq(argv[i + 1]);
            button_port = argv[i + 1][0];
            button_bit = argv[i + 1][1] - '0';
            button_tick(avr, avr->cycle, NULL);
            avr_cycle_timer_register(avr, avr->frequency / 1000, button_tick, NULL);
        } else if (!strcmp(argv[i], "--adc")) {
            adc = avr_io_getirq(avr, AVR_IOCTL_ADC_GETIRQ, ADC_IRQ_ADC0 + atoi(argv[i + 1]));
            adc_tick(avr, avr->cycle, NULL);
            avr_cycle_timer_register(avr, avr->frequency / 1000, adc_tick, NULL);
        } else {
            fprintf(stderr, "evia_sim: unknown option %s\n", argv[i]);
            return 2;
        }
    }

    avr_cycle_count_t limit = (avr_cycle_count_t) (seconds * avr->frequency);
    int state = cpu_Running;
    while (state != cpu_Done && state != cpu_Crashed) {
        state = avr_run(avr);
        if (avr->cycle > limit) {
            fflush(stdout);
            fprintf(stderr, "evia_sim: still running after %.0f s of simulated time\n", seconds);
            return 3;
        }
    }
    fflush(stdout);
    return state == cpu_Crashed ? 4 : 0;
}
